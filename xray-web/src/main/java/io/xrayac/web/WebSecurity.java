package io.xrayac.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions, CSRF tokens and login throttling.
 *
 * <p>State lives in memory only. Sessions do not survive a restart and are not persisted: an admin
 * console whose credentials are one password over plain HTTP gains nothing from durable sessions, and
 * a restart that forces a fresh login is a feature.
 *
 * <h2>What each defence is for</h2>
 * <ul>
 *   <li><b>Opaque session ids</b> from {@link Credentials#newToken(int)} - guessing one is not
 *       feasible, and they are compared in constant time.</li>
 *   <li><b>Idle expiry</b> so an unattended browser does not stay authenticated indefinitely, plus an
 *       absolute cap so a session cannot be kept alive forever by polling.</li>
 *   <li><b>CSRF tokens</b> bound to the session and required on every state-changing request. The
 *       console is reachable from a browser on this machine, so a page from any other site the
 *       operator visits could otherwise POST to it and act on their behalf - the browser will happily
 *       send the session cookie.</li>
 *   <li><b>Per-address failure throttling</b> to make online guessing of the password impractical.</li>
 * </ul>
 */
public final class WebSecurity {

    /** Absolute session lifetime, regardless of activity. */
    private static final Duration ABSOLUTE_CAP = Duration.ofHours(12);

    private final int idleMinutes;
    private final int maxFailedLogins;
    private final int lockoutMinutes;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public WebSecurity(WebConfig config) {
        this.idleMinutes = config.sessionMinutes();
        this.maxFailedLogins = config.maxFailedLogins();
        this.lockoutMinutes = config.lockoutMinutes();
    }

    /** A live session. Mutable only in {@code lastSeen}, which is updated on each authenticated request. */
    public static final class Session {
        private final String id;
        private final String csrfToken;
        private final String clientAddress;
        private final Instant createdAt;
        private volatile Instant lastSeen;

        private Session(String id, String csrfToken, String clientAddress) {
            this.id = id;
            this.csrfToken = csrfToken;
            this.clientAddress = clientAddress;
            this.createdAt = Instant.now();
            this.lastSeen = this.createdAt;
        }

        public String id() {
            return id;
        }

        public String csrfToken() {
            return csrfToken;
        }

        public String clientAddress() {
            return clientAddress;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant lastSeen() {
            return lastSeen;
        }
    }

    private static final class Failures {
        private int count;
        private Instant lockedUntil;
    }

    /** Starts a new session for an authenticated operator. */
    public Session create(String clientAddress) {
        // 32 bytes of entropy: the id is the entire authentication proof for subsequent requests.
        Session session = new Session(
                Credentials.newToken(32),
                Credentials.newToken(32),
                clientAddress == null ? "unknown" : clientAddress);
        sessions.put(session.id(), session);
        return session;
    }

    /**
     * Resolves a session id, returning empty when it is unknown or expired.
     *
     * <p>Expiry is evaluated on use rather than by a background sweep, so there is no window in which
     * an expired session is still accepted while a reaper has not run yet.
     */
    public Optional<Session> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (Duration.between(session.lastSeen(), now).toMinutes() >= idleMinutes
                || Duration.between(session.createdAt(), now).compareTo(ABSOLUTE_CAP) >= 0) {
            sessions.remove(session.id());
            return Optional.empty();
        }
        session.lastSeen = now;
        return Optional.of(session);
    }

    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Drops expired sessions. Called periodically so an abandoned browser does not pin memory. */
    public void sweep() {
        Instant now = Instant.now();
        sessions.values().removeIf(s -> Duration.between(s.lastSeen(), now).toMinutes() >= idleMinutes
                || Duration.between(s.createdAt(), now).compareTo(ABSOLUTE_CAP) >= 0);
        failures.values().removeIf(f -> f.lockedUntil != null && f.lockedUntil.isBefore(now));
    }

    public int activeSessions() {
        return sessions.size();
    }

    /** True while an address is locked out after too many failed logins. */
    public boolean isLockedOut(String address) {
        Failures record = failures.get(key(address));
        if (record == null || record.lockedUntil == null) {
            return false;
        }
        if (record.lockedUntil.isBefore(Instant.now())) {
            failures.remove(key(address));
            return false;
        }
        return true;
    }

    /** Seconds remaining on a lockout, or 0 when there is none. */
    public long lockoutSecondsRemaining(String address) {
        Failures record = failures.get(key(address));
        if (record == null || record.lockedUntil == null) {
            return 0;
        }
        long seconds = Duration.between(Instant.now(), record.lockedUntil).toSeconds();
        return Math.max(0, seconds);
    }

    /**
     * Records a failed login and locks the address out once the threshold is reached.
     *
     * @return true if this failure triggered a lockout
     */
    public boolean recordFailure(String address) {
        Failures record = failures.computeIfAbsent(key(address), k -> new Failures());
        synchronized (record) {
            record.count++;
            if (record.count >= maxFailedLogins) {
                record.lockedUntil = Instant.now().plus(Duration.ofMinutes(lockoutMinutes));
                record.count = 0;
                return true;
            }
            return false;
        }
    }

    /** Clears the failure counter after a successful login. */
    public void recordSuccess(String address) {
        failures.remove(key(address));
    }

    private static String key(String address) {
        return address == null ? "unknown" : address;
    }

    /**
     * The Set-Cookie value for a session.
     *
     * <p>{@code HttpOnly} keeps the token away from JavaScript; {@code SameSite=Strict} is what
     * actually stops another site's page from causing an authenticated request, and is affordable here
     * because nothing legitimate links into this console. {@code Secure} is deliberately absent: the
     * panel speaks plain HTTP on loopback, and setting it would stop the browser from sending the
     * cookie at all, breaking the login outright.
     */
    public String cookie(String sessionId, Duration maxAge) {
        return "xray_session=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge.toSeconds();
    }

    /** The cookie that clears the session. */
    public String clearingCookie() {
        return "xray_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    /** Extracts the session id from a raw Cookie header. */
    public static Optional<String> sessionIdFromCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("xray_session=")) {
                String value = trimmed.substring("xray_session=".length());
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
