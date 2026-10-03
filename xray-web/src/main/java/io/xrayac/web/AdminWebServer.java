package io.xrayac.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.xrayac.core.repository.BanWaveRepository;
import io.xrayac.core.repository.ModeratorActionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The embedded administration web server.
 *
 * <p>Built on {@code com.sun.net.httpserver}, which is part of the JDK. That is the whole reason this
 * feature adds no length to the plugin jar: no web framework, no JSON library, no template engine, no
 * logging bridge. The trade is that the HTTP layer's conveniences have to be written here - routing,
 * cookie parsing, form decoding, response headers, static asset serving - and each of those is a place
 * where a shortcut becomes a vulnerability, so they are written explicitly rather than cleverly.
 *
 * <h2>Security posture</h2>
 * The threat model is deliberate and worth stating, because "secure" without one is meaningless. The
 * panel is expected to be reachable only from the machine the server runs on, or through a tunnel the
 * operator controls. Given that:
 *
 * <ul>
 *   <li><b>It binds to loopback by default</b> and refuses a non-loopback address unless
 *       {@code allow-non-loopback} is set, because the transport is plain HTTP - there is no TLS here,
 *       since terminating TLS needs either a dependency or a certificate lifecycle this feature cannot
 *       reasonably own.</li>
 *   <li><b>Authentication is one password, hashed with PBKDF2</b> and compared in constant time, with
 *       per-address lockout after repeated failures.</li>
 *   <li><b>Sessions are opaque, random, idle-expiring and bounded by an absolute cap</b>, carried in an
 *       {@code HttpOnly; SameSite=Strict} cookie.</li>
 *   <li><b>Every state-changing request needs a session-bound CSRF token.</b> Without this, any page the
 *       operator visits could POST to {@code 127.0.0.1:8099} and the browser would attach the cookie.</li>
 *   <li><b>All output is escaped</b> at the point of rendering, and the CSP forbids inline script and
 *       any external origin, so a stored string cannot become executable markup.</li>
 *   <li><b>Reads are bounded</b> by the configured page size, so opening a page on a busy server cannot
 *       become a self-inflicted denial of service.</li>
 *   <li><b>The panel cannot edit evidence.</b> It reads assessments and writes audit entries. The only
 *       changes it can make to the game are through {@link ModerationActions}, which the platform
 *       adapter implements and which reports honestly whether it acted.</li>
 * </ul>
 *
 * <p>What it does <em>not</em> defend against: an attacker who already has local access to the machine,
 * or who can read the configuration file. Both are out of scope for a plugin-embedded console, and
 * claiming otherwise would be dishonest.
 */
public final class AdminWebServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminWebServer.class);

    /** Body cap. Every form the panel serves is a handful of fields; anything larger is not for us. */
    private static final int MAX_BODY_BYTES = 16 * 1024;

    /**
     * The actor identity recorded for panel actions.
     *
     * <p>A fixed, documented, non-player UUID. It must not be a real player's id: the audit trail
     * distinguishes "a moderator did this" from "the plugin did this", and attributing console actions
     * to whichever player happened to be first in the list would corrupt exactly the record that makes
     * moderation accountable.
     */
    public static final UUID PANEL_ACTOR = UUID.fromString("00000000-0000-0000-0000-00000000ac01");

    /**
     * How far back the player page looks for discoveries, mining and tallies.
     *
     * <p>Deliberately matched to the discovery retention default rather than a round number: a window
     * shorter than retention would show "no discoveries" for a player whose discoveries are merely
     * older than the window, which reads as missing data rather than as an arbitrary cut-off. The page
     * states the window it used for that reason.
     */
    private static final Duration DISPLAY_WINDOW = Duration.ofDays(90);

    private final WebConfig config;
    private final WebData data;
    private final ModerationActions actions;
    private final WebSecurity security;

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService reaper;

    public AdminWebServer(WebConfig config, WebData data, ModerationActions actions) {
        this.config = config;
        this.data = data;
        this.actions = actions;
        this.security = new WebSecurity(config);
    }

    /**
     * Starts listening.
     *
     * @throws IOException if the address cannot be bound, which the caller must report rather than
     *                     swallow: a panel the operator believes is running but is not is worse than one
     *                     that failed loudly
     */
    public void start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(config.effectiveBindAddress(), config.port());
        server = HttpServer.create(address, 64);

        // A small fixed pool. The default executor runs handlers on the dispatcher thread, so one slow
        // request would block the accept loop; an unbounded pool would let a flood spawn threads
        // without limit. Four threads is far more than a local admin console needs.
        executor = Executors.newFixedThreadPool(4, namedThreads());
        server.setExecutor(executor);

        server.createContext("/", this::dispatch);

        // create() binds the socket but does NOT accept connections: without start() the port is
        // open, every request connects, and none is ever answered. A bound-but-not-started server
        // looks healthy from the outside (the port answers a TCP connect) and simply never replies,
        // which is the worst possible failure mode for a panel an operator is trying to reach.
        server.start();

        // A periodic sweep so an abandoned browser tab does not keep a session alive in memory
        // indefinitely. Sessions are also expiry-checked on use, so this only reclaims memory; it is
        // not load-bearing for correctness. Daemon thread: it must never hold up JVM shutdown.
        reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xray-web-reaper");
            thread.setDaemon(true);
            return thread;
        });
        reaper.scheduleWithFixedDelay(security::sweep, 1, 1, TimeUnit.MINUTES);
    }

    /** Stops listening and releases the port. Safe to call when not running. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (reaper != null) {
            reaper.shutdownNow();
            reaper = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        return server == null ? config.port() : server.getAddress().getPort();
    }

    /** The address actually bound, which is what the startup log should report. */
    public String boundAddress() {
        return server == null ? "(not running)" : server.getAddress().toString();
    }

    /** How many operators are signed in. */
    public int activeSessions() {
        return security.activeSessions();
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "xray-web-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------------------------------

    private void dispatch(HttpExchange exchange) {
        try (exchange) {
            applySecurityHeaders(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (!"GET".equals(method) && !"POST".equals(method) && !"HEAD".equals(method)) {
                // No PUT/PATCH/DELETE and no method override: the panel has three verbs and inventing
                // more would only widen the attack surface.
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                sendText(exchange, 405, "Method Not Allowed", "Only GET and POST are served.");
                return;
            }

            if (path.startsWith("/assets/")) {
                serveAsset(exchange, path.substring("/assets/".length()));
                return;
            }

            // Unauthenticated surface: the login page and the login submission. Everything else needs
            // a session, and the check happens before any handler runs so a new route cannot forget it.
            if (path.equals("/login")) {
                if ("POST".equals(method)) {
                    handleLogin(exchange);
                } else {
                    sendHtml(exchange, 200, Pages.login(null, null));
                }
                return;
            }
            if (path.equals("/api/health")) {
                // Deliberately minimal and unauthenticated so a supervisor can probe liveness, and
                // deliberately says nothing about the data.
                sendJson(exchange, 200, Json.obj(Json.map()));
                return;
            }

            Optional<WebSecurity.Session> session = currentSession(exchange);
            if (session.isEmpty()) {
                // A browser should be sent to the login page; a script should be told plainly that it
                // is not authenticated. Handing a JSON client a 303 to an HTML login page is how a
                // caller ends up parsing a page and concluding something about the data.
                if ("POST".equals(method) || path.startsWith("/api/")) {
                    sendText(exchange, 401, "Unauthorised", "Sign in first.");
                } else {
                    exchange.getResponseHeaders().set("Location", "/login");
                    sendText(exchange, 303, "See Other", "Sign in to continue.");
                }
                return;
            }

            WebSecurity.Session active = session.get();

            if (path.equals("/logout")) {
                if (requireCsrf(exchange, active)) {
                    security.invalidate(active.id());
                    exchange.getResponseHeaders().set("Set-Cookie", security.clearingCookie());
                    exchange.getResponseHeaders().set("Location", "/login");
                    sendText(exchange, 303, "See Other", "Signed out.");
                }
                return;
            }

            if ("GET".equals(method) || "HEAD".equals(method)) {
                routeGet(exchange, path, active);
                return;
            }

            // Every remaining POST changes something, so the CSRF token is mandatory before routing.
            if (!requireCsrf(exchange, active)) {
                return;
            }
            routePost(exchange, path, active);
        } catch (IOException e) {
            LOGGER.debug("panel request failed while writing a response", e);
        } catch (RuntimeException e) {
            // A repository failure is a normal outcome of a database going away, not a crash. It is
            // reported as a 500 page and logged once, and the process stays up.
            LOGGER.warn("panel request failed", e);
            try {
                sendText(exchange, 500, "Internal error",
                        "The request could not be completed. Check the server log.");
            } catch (IOException ignored) {
                // The connection is already gone; nothing useful remains to do.
            }
        }
    }

    private void routeGet(HttpExchange exchange, String path, WebSecurity.Session session) throws IOException {
        String page = query(exchange).getOrDefault("page", "1");
        int requestedPage = parseInt(page, 1);

        if (path.equals("/") || path.equals("")) {
            var overview = data.overview();
            sendHtml(exchange, 200, Pages.dashboard(
                    overview,
                    data.candidates(),
                    data.recentActions(6),
                    security.activeSessions(),
                    session.csrfToken()));
            return;
        }

        if (path.equals("/players")) {
            String filter = query(exchange).getOrDefault("q", "").trim();
            List<WebData.PlayerRow> rows = filtered(data.players(PAGE_FETCH), filter);
            int from = Math.min(rows.size(), Math.max(0, (requestedPage - 1) * config.pageSize()));
            int to = Math.min(rows.size(), from + config.pageSize());
            sendHtml(exchange, 200, Pages.players(
                    rows.subList(from, to),
                    requestedPage,
                    config.pageSize(),
                    to < rows.size(),
                    filter,
                    session.csrfToken()));
            return;
        }

        if (path.startsWith("/players/")) {
            Optional<UUID> id = parseUuid(path.substring("/players/".length()));
            if (id.isEmpty()) {
                sendHtml(exchange, 404, Pages.error(404, "Not a valid player id."));
                return;
            }
            Optional<WebData.PlayerRow> row = data.playerOrCandidate(id.get());
            if (row.isEmpty()) {
                sendHtml(exchange, 404, Pages.error(404, "No stored record for that player."));
                return;
            }
            boolean online = actions.online().stream().anyMatch(p -> p.id().equals(id.get()));
            sendHtml(exchange, 200, Pages.player(
                    row.get(),
                    data.history(id.get(), 50),
                    data.discoveries(id.get(), DISPLAY_WINDOW, 200),
                    data.actionsFor(id.get(), 50),
                    data.oreTally(id.get(), DISPLAY_WINDOW, 200),
                    Pages.windowLabel(DISPLAY_WINDOW),
                    online,
                    config.readOnly(),
                    List.of(),
                    session.csrfToken()));
            return;
        }

        if (path.equals("/banwave")) {
            sendHtml(exchange, 200, Pages.banWave(data.candidates(), data.waves(25), null, session.csrfToken()));
            return;
        }

        if (path.equals("/audit")) {
            sendHtml(exchange, 200, Pages.audit(data.recentActions(200), session.csrfToken()));
            return;
        }

        if (path.equals("/api/stats")) {
            sendJson(exchange, 200, statsJson());
            return;
        }

        sendHtml(exchange, 404, Pages.error(404, "No such page."));
    }

    private void routePost(HttpExchange exchange, String path, WebSecurity.Session session) throws IOException {
        if (path.startsWith("/players/") && path.endsWith("/action")) {
            String raw = path.substring("/players/".length(), path.length() - "/action".length());
            Optional<UUID> id = parseUuid(raw);
            if (id.isEmpty()) {
                sendHtml(exchange, 404, Pages.error(404, "Not a valid player id."));
                return;
            }
            handlePlayerAction(exchange, id.get(), session);
            return;
        }

        if (path.equals("/banwave/dismiss")) {
            Optional<UUID> id = parseUuid(form(exchange).getOrDefault("player", ""));
            if (id.isEmpty()) {
                sendHtml(exchange, 400, Pages.error(400, "Not a valid player id."));
                return;
            }
            data.recordAction(id.get(), "dismiss-candidate",
                    "Candidate removed from the ban wave through the panel");
            data.dismissCandidate(id.get());
            exchange.getResponseHeaders().set("Location", "/banwave");
            sendText(exchange, 303, "See Other", "Candidate dismissed.");
            return;
        }

        sendHtml(exchange, 404, Pages.error(404, "No such endpoint."));
    }

    /**
     * Handles one moderation action from the player page.
     *
     * <p>Every branch records the audit entry, and every branch reports what actually happened. A kick
     * for a player who has already left is reported as "not online" rather than as success, because an
     * interface that claims to have done something it did not quickly stops being trusted.
     */
    private void handlePlayerAction(HttpExchange exchange, UUID playerId, WebSecurity.Session session)
            throws IOException {
        Map<String, String> form = form(exchange);
        String action = form.getOrDefault("action", "").trim();
        String reason = form.getOrDefault("reason", "").trim();
        if (reason.length() > 220) {
            reason = reason.substring(0, 220);
        }

        if (config.readOnly() && !action.equals("note")) {
            renderPlayerWithErrors(exchange, playerId, session,
                    List.of("Moderation is disabled: the panel is in read-only mode."));
            return;
        }

        String note = reason.isEmpty() ? "(no reason given)" : reason;
        switch (action) {
            case "note" -> {
                data.recordAction(playerId, "note", note);
                LOGGER.info("panel: note recorded for {}", playerId);
                renderPlayerWithErrors(exchange, playerId, session, List.of());
            }
            case "message" -> {
                boolean delivered = actions.message(playerId, reason.isEmpty()
                        ? "A moderator is reviewing your mining activity."
                        : reason);
                data.recordAction(playerId, "message", delivered
                        ? note : note + " [not delivered: player offline]");
                renderPlayerWithErrors(exchange, playerId, session, delivered
                        ? List.of()
                        : List.of("The message was not delivered: that player is not online."));
            }
            case "kick" -> {
                boolean kicked = actions.kick(playerId, reason.isEmpty() ? "Kicked by a moderator" : reason);
                data.recordAction(playerId, "kick", kicked ? note : note + " [not online]");
                LOGGER.info("panel: kick for {} -> {}", playerId, kicked);
                renderPlayerWithErrors(exchange, playerId, session, kicked
                        ? List.of()
                        : List.of("Nothing was kicked: that player is not online."));
            }
            case "ban" -> {
                boolean banned = actions.ban(playerId, reason.isEmpty() ? "Banned by a moderator" : reason);
                data.recordAction(playerId, "ban", banned ? note : note + " [not applied]");
                LOGGER.warn("panel: ban for {} -> {}", playerId, banned);
                renderPlayerWithErrors(exchange, playerId, session, banned
                        ? List.of()
                        : List.of("The ban was not applied. The server reported that it could not do it."));
            }
            case "unban" -> {
                boolean unbanned = actions.unban(playerId);
                data.recordAction(playerId, "unban", note);
                LOGGER.warn("panel: unban for {} -> {}", playerId, unbanned);
                renderPlayerWithErrors(exchange, playerId, session, unbanned
                        ? List.of()
                        : List.of("No ban was found to lift."));
            }
            default -> sendHtml(exchange, 400, Pages.error(400, "Unknown action."));
        }
    }

    private void renderPlayerWithErrors(HttpExchange exchange, UUID playerId,
                                        WebSecurity.Session session, List<String> errors) throws IOException {
        Optional<WebData.PlayerRow> row = data.playerOrCandidate(playerId);
        if (row.isEmpty()) {
            sendHtml(exchange, 404, Pages.error(404, "No stored record for that player."));
            return;
        }
        boolean online = actions.online().stream().anyMatch(p -> p.id().equals(playerId));
        sendHtml(exchange, 200, Pages.player(
                row.get(),
                data.history(playerId, 50),
                data.discoveries(playerId, DISPLAY_WINDOW, 200),
                data.actionsFor(playerId, 50),
                data.oreTally(playerId, DISPLAY_WINDOW, 200),
                Pages.windowLabel(DISPLAY_WINDOW),
                online,
                config.readOnly(),
                errors,
                session.csrfToken()));
    }

    /** How many rows the list endpoint fetches before paging in memory. */
    private static final int PAGE_FETCH = 1000;

    private static List<WebData.PlayerRow> filtered(List<WebData.PlayerRow> rows, String filter) {
        if (filter.isBlank()) {
            return rows;
        }
        String needle = filter.toLowerCase(java.util.Locale.ROOT);
        return rows.stream()
                .filter(row -> (row.name() != null && row.name().toLowerCase(java.util.Locale.ROOT).contains(needle))
                        || row.id().toString().startsWith(needle))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Authentication
    // ---------------------------------------------------------------------------------------------

    private void handleLogin(HttpExchange exchange) throws IOException {
        String client = clientAddress(exchange);

        if (security.isLockedOut(client)) {
            long seconds = security.lockoutSecondsRemaining(client);
            LOGGER.warn("panel: login refused for a locked-out address ({}s remaining)", seconds);
            sendHtml(exchange, 429, Pages.login(
                    "Too many failed attempts. Try again in " + (seconds / 60 + 1) + " minute(s).", null));
            return;
        }

        Map<String, String> form = form(exchange);
        String username = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");

        boolean userMatches = Credentials.constantTimeEquals(username, config.username());
        boolean passwordMatches = Credentials.verify(password, config.passwordHash());

        if (!userMatches || !passwordMatches) {
            boolean locked = security.recordFailure(client);
            LOGGER.warn("panel: failed sign-in from {} (locked out: {})", client, locked);
            // One message for both cases: distinguishing "no such user" from "wrong password" would
            // confirm the username to anyone guessing.
            sendHtml(exchange, locked ? 429 : 401, Pages.login(
                    locked
                            ? "Too many failed attempts. The panel is locked for a few minutes."
                            : "Incorrect username or password.",
                    null));
            return;
        }

        security.recordSuccess(client);
        WebSecurity.Session session = security.create(client);
        exchange.getResponseHeaders().set("Set-Cookie",
                security.cookie(session.id(), Duration.ofMinutes(config.sessionMinutes())));
        exchange.getResponseHeaders().set("Location", "/");
        LOGGER.info("panel: successful sign-in from {}", client);
        sendText(exchange, 303, "See Other", "Signed in.");
    }

    private Optional<WebSecurity.Session> currentSession(HttpExchange exchange) {
        return WebSecurity.sessionIdFromCookie(exchange.getRequestHeaders().getFirst("Cookie"))
                .flatMap(security::resolve);
    }

    /**
     * Enforces the CSRF token on a state-changing request.
     *
     * @return true when the request may proceed; false when a response has already been sent
     */
    private boolean requireCsrf(HttpExchange exchange, WebSecurity.Session session) throws IOException {
        String supplied = form(exchange).getOrDefault("csrf", "");
        if (!Credentials.constantTimeEquals(supplied, session.csrfToken())) {
            LOGGER.warn("panel: rejected a request with a missing or invalid CSRF token");
            sendHtml(exchange, 403, Pages.error(403,
                    "That request was rejected because its anti-forgery token was missing or wrong. "
                            + "Reload the page and try again."));
            return false;
        }
        return true;
    }

    /**
     * The client address, honouring {@code X-Forwarded-For} only when the operator has said a proxy is
     * in front.
     *
     * <p>Trusting that header unconditionally would let anyone bypass the per-address login lockout by
     * forging a new address on every attempt, which is why it is opt-in.
     */
    private String clientAddress(HttpExchange exchange) {
        if (config.behindProxy()) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        var address = exchange.getRemoteAddress();
        return address == null ? "unknown" : address.getAddress().getHostAddress();
    }

    // ---------------------------------------------------------------------------------------------
    // Request parsing and response writing
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads and decodes an urlencoded body.
     *
     * <p>Cached per exchange so a handler that checks the CSRF token and then reads a field does not
     * consume the stream twice - the second read would silently return an empty map and every form
     * would appear blank.
     */
    private static final String FORM_CACHE = "io.xrayac.web.form";

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        Object cached = exchange.getAttribute(FORM_CACHE);
        if (cached instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, String> typed = (Map<String, String>) map;
            return typed;
        }
        String body = readBody(exchange);
        // A GET carries its parameters in the query string; a POST carries them in the body. Reading
        // the body first and falling back to the query is what lets the same lookup work for both.
        Map<String, String> parsed = (body == null || body.isBlank()) ? query(exchange) : decode(body);
        exchange.setAttribute(FORM_CACHE, parsed);
        return parsed;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = in.readNBytes(MAX_BODY_BYTES + 1);
            if (buffer.length > MAX_BODY_BYTES) {
                throw new IOException("request body larger than " + MAX_BODY_BYTES + " bytes");
            }
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> query(HttpExchange exchange) {
        return decode(exchange.getRequestURI().getRawQuery());
    }

    /** Decodes urlencoded key/value pairs, first value winning on a repeated key. */
    private static Map<String, String> decode(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                values.putIfAbsent(
                        URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // A malformed percent-escape is a client error, not a server error: skip the pair
                // rather than failing the whole request.
                LOGGER.debug("panel: ignored a malformed form pair");
            }
        }
        return values;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /**
     * Headers applied to every response.
     *
     * <p>The CSP is the important one: {@code default-src 'none'} with only same-origin styles, scripts
     * and images allowed. No inline script, so an injected {@code <script>} cannot execute; no external
     * origin, so the panel cannot be made to leak data to a third party and does not phone home.
     */
    private static void applySecurityHeaders(Headers headers) {
        headers.set("Content-Security-Policy",
                "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        // Player data must not be retained by a shared browser or an intermediary.
        headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        send(exchange, status, body);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body);
    }

    private void sendText(HttpExchange exchange, int status, String title, String message) throws IOException {
        sendHtml(exchange, status, Pages.layout(title, false, null,
                "<div class=\"empty card\"><h1>" + Html.esc(title) + "</h1><p>"
                        + Html.esc(message) + "</p></div>", ""));
    }

    private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            // A HEAD response carries the headers and no body; sending one with -1 does exactly that.
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Serves a static asset from this module's own resources.
     *
     * <p>Assets are looked up in a fixed map rather than resolved against the filesystem, so there is no
     * path to traverse - a request for {@code /assets/../../config.yml} simply finds nothing.
     */
    private void serveAsset(HttpExchange exchange, String name) throws IOException {
        String resource = switch (name) {
            case "app.css" -> "/web/app.css";
            case "app.js" -> "/web/app.js";
            default -> null;
        };
        if (resource == null) {
            sendText(exchange, 404, "Not found", "No such asset.");
            return;
        }
        try (InputStream in = AdminWebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendText(exchange, 404, "Not found", "The asset is missing from the jar.");
                return;
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", name.endsWith(".css")
                    ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8");
            // Short-lived: an upgraded panel must not keep serving a stale interface from a browser
            // cache, and the panel is not a high-traffic site where caching matters.
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60");
            send(exchange, 200, body);
        }
    }

    /** A small authenticated JSON summary, for scripting and for the dashboard's live refresh. */
    private String statsJson() {
        WebData.Overview overview = data.overview();
        Map<String, String> json = Json.map();
        json.put("trackedPlayers", Json.num(overview.trackedPlayers()));
        json.put("trackedWindow", Json.num(overview.trackedLimit()));
        json.put("candidates", Json.num(overview.candidates()));
        json.put("worldRemovals", Json.num(overview.worldRemovals()));
        json.put("dialect", Json.str(overview.dialect()));
        json.put("schemaVersion", Json.num(overview.schemaVersion()));
        json.put("activeSessions", Json.num(security.activeSessions()));
        json.put("time", Json.str(Instant.now().toString()));
        return Json.obj(json);
    }

    /** Exposed for tests: the repository facade this server serves from. */
    WebData data() {
        return data;
    }
}
