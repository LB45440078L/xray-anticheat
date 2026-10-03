package io.xrayac.web;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the embedded administration panel.
 *
 * <p>A plain record, deliberately: this module has no Minecraft or YAML dependency, so the platform
 * adapter reads {@code config.yml} and constructs this. That keeps the panel testable without a server
 * and keeps all configuration parsing in one place.
 *
 * <p>The defaults are chosen to be safe rather than convenient. In particular the bind address is
 * loopback and {@link #allowNonLoopback} must be set explicitly before the panel will listen on
 * anything else - see {@link #problems()} for why that is enforced rather than merely documented.
 *
 * @param enabled          whether the panel starts at all
 * @param bindAddress      interface to bind; loopback by default
 * @param port             TCP port; 8099 by default
 * @param username         the single administrator account name
 * @param passwordHash     a value produced by {@link Credentials#hash(String)}; empty until set
 * @param sessionMinutes   how long an idle session stays valid
 * @param maxFailedLogins  failed attempts from one address before lockout
 * @param lockoutMinutes   how long a locked-out address must wait
 * @param pageSize         rows per page in the listings
 * @param allowNonLoopback must be true for any bind address other than loopback
 * @param readOnly         when true, every moderation action is refused and the UI hides the controls
 * @param behindProxy      when true, trust X-Forwarded-For for the client address
 */
public record WebConfig(
        boolean enabled,
        String bindAddress,
        int port,
        String username,
        String passwordHash,
        int sessionMinutes,
        int maxFailedLogins,
        int lockoutMinutes,
        int pageSize,
        boolean allowNonLoopback,
        boolean readOnly,
        boolean behindProxy) {

    public static final int DEFAULT_PORT = 8099;

    public static WebConfig defaults() {
        return new WebConfig(
                false,
                "127.0.0.1",
                DEFAULT_PORT,
                "admin",
                "",
                60,
                5,
                15,
                25,
                false,
                false,
                false);
    }

    /** True when the configured bind address is a loopback address. */
    public boolean isLoopbackAddress() {
        if (bindAddress == null) {
            return false;
        }
        String address = bindAddress.trim();
        if (address.equals("localhost") || address.equals("::1") || address.equals("[::1]")) {
            return true;
        }
        // Any 127.x.x.x address is loopback; the whole /8 is reserved for it.
        return address.startsWith("127.");
    }

    /**
     * Problems that must prevent the panel from starting.
     *
     * <p>The bind-address rule is here rather than in a comment because it is the single most
     * consequential setting in this feature. The panel exposes player data and can ban or kick, and it
     * authenticates with one username and one password over plain HTTP - there is no TLS, because
     * terminating TLS would add a dependency this module exists to avoid. Bound to loopback that is an
     * acceptable trade, behind SSH or a tunnel. Bound to a public interface it would put a
     * password-authenticated moderation console in plaintext on the internet, so it is refused unless
     * the operator has said, explicitly, that they know.
     *
     * @return the list of fatal problems, empty when the configuration is usable
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (bindAddress == null || bindAddress.isBlank()) {
            problems.add("bind-address is empty: set it to 127.0.0.1 (loopback) or an explicit interface");
        }
        if (port < 1 || port > 65535) {
            problems.add("port must be between 1 and 65535, but is " + port);
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            problems.add("no password-hash is set: the panel refuses to serve without a password, "
                    + "because an empty credential would be an open moderation console. Remove it "
                    + "from the configuration to have the plugin generate one at startup.");
        }
        if (!isLoopbackAddress() && !allowNonLoopback) {
            problems.add("bind-address " + bindAddress + " is not loopback, but allow-non-loopback is "
                    + "false. The panel serves plain HTTP and authenticates with a single password, so "
                    + "exposing it beyond this machine must be an explicit decision: either bind "
                    + "127.0.0.1 and reach it through a tunnel, or set allow-non-loopback: true having "
                    + "considered what that means.");
        }
        if (sessionMinutes < 1) {
            problems.add("session-minutes must be at least 1, but is " + sessionMinutes);
        }
        if (maxFailedLogins < 1) {
            problems.add("max-failed-logins must be at least 1, but is " + maxFailedLogins);
        }
        if (pageSize < 1 || pageSize > 500) {
            problems.add("page-size must be between 1 and 500, but is " + pageSize);
        }
        if (username == null || username.isBlank()) {
            problems.add("username is empty");
        }
        return problems;
    }

    /**
     * Non-fatal observations worth logging at startup.
     *
     * @return the list of warnings, empty when there is nothing to say
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (!isLoopbackAddress()) {
            warnings.add("the panel is bound to " + bindAddress + " rather than loopback and is "
                    + "reachable over the network in plain HTTP. Put it behind a tunnel or restrict "
                    + "access at the firewall.");
        }
        if (behindProxy) {
            warnings.add("behind-proxy is on, so the panel trusts the X-Forwarded-For header for the "
                    + "client address. Only safe if a reverse proxy you control always sets it: if the "
                    + "panel is directly reachable, an attacker can forge that header and evade "
                    + "per-address lockouts.");
        }
        if (readOnly) {
            warnings.add("read-only is on: moderation actions are disabled and the panel is an "
                    + "inspection tool only.");
        }
        if (sessionMinutes > 24 * 60) {
            warnings.add("session-minutes is " + sessionMinutes + ", so a session can stay valid for "
                    + "more than a day. Shorter is safer.");
        }
        return warnings;
    }

    /** The bind address to actually use, with wildcard forms normalised. */
    public String effectiveBindAddress() {
        String address = bindAddress == null ? "127.0.0.1" : bindAddress.trim();
        if (address.equals("[::1]")) {
            return "::1";
        }
        return address;
    }
}
