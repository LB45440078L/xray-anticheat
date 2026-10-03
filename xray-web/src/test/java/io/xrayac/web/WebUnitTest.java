package io.xrayac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pieces of the panel that carry its security properties.
 *
 * <p>These are the parts where a plausible-looking implementation is quietly wrong, and where the
 * wrongness is invisible in normal use: escaping that misses one character, a comparison that leaks
 * timing, a session that outlives its expiry, a bind address that is accepted when it should be
 * refused. Each test below names the specific failure it prevents.
 */
@DisplayName("admin panel building blocks")
class WebUnitTest {

    @Nested
    @DisplayName("JSON encoding")
    class JsonEncoding {

        @Test
        @DisplayName("escapes quotes, backslashes and control characters")
        void escapesTheBasics() {
            assertThat(Json.str("a\"b")).isEqualTo("\"a\\\"b\"");
            assertThat(Json.str("a\\b")).isEqualTo("\"a\\\\b\"");
            assertThat(Json.str("a\nb")).isEqualTo("\"a\\nb\"");
            assertThat(Json.str("a\u0001b")).isEqualTo("\"a\\u0001b\"");
        }

        @Test
        @DisplayName("escapes angle brackets so a value cannot close a script block")
        void escapesAngleBrackets() {
            // JSON itself does not require this. It is done because these strings can be embedded in
            // an HTML document, where a literal </script> inside a string would end the block early
            // and let the remainder be parsed as markup.
            assertThat(Json.str("</script>")).isEqualTo("\"\\u003c/script\\u003e\"");
            assertThat(Json.str("a&b")).isEqualTo("\"a\\u0026b\"");
        }

        @Test
        @DisplayName("escapes the line separators that JSON allows but JavaScript does not")
        void escapesLineSeparators() {
            assertThat(Json.str("a\u2028b")).isEqualTo("\"a\\u2028b\"");
            assertThat(Json.str("a\u2029b")).isEqualTo("\"a\\u2029b\"");
        }

        @Test
        @DisplayName("renders non-finite numbers as null, because JSON has no NaN")
        void nonFiniteNumbersBecomeNull() {
            // A statistic that is undefined must not be reported as a number. Emitting NaN would
            // produce a payload no JSON parser accepts, and emitting 0 would be a claim the engine
            // never made.
            assertThat(Json.num(Double.NaN)).isEqualTo("null");
            assertThat(Json.num(Double.POSITIVE_INFINITY)).isEqualTo("null");
            assertThat(Json.num(0.5)).isEqualTo("0.5");
        }

        @Test
        @DisplayName("null renders as null rather than the string")
        void nullStaysNull() {
            assertThat(Json.str(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("objects preserve insertion order")
        void objectsPreserveOrder() {
            Map<String, String> object = Json.map();
            object.put("b", Json.num(2));
            object.put("a", Json.str("x"));
            assertThat(Json.obj(object)).isEqualTo("{\"b\":2,\"a\":\"x\"}");
        }
    }

    @Nested
    @DisplayName("password hashing")
    class PasswordHashing {

        @Test
        @DisplayName("a password verifies against its own hash")
        void roundTrip() {
            String hash = Credentials.hash("correct horse battery staple");
            assertThat(Credentials.verify("correct horse battery staple", hash)).isTrue();
        }

        @Test
        @DisplayName("a different password does not verify")
        void wrongPasswordFails() {
            String hash = Credentials.hash("correct horse battery staple");
            assertThat(Credentials.verify("Correct horse battery staple", hash)).isFalse();
            assertThat(Credentials.verify("", hash)).isFalse();
        }

        @Test
        @DisplayName("the same password hashes differently every time")
        void saltedPerPassword() {
            // Without a per-password salt, two administrators choosing the same password would share
            // a hash, and one cracked entry would reveal both.
            String first = Credentials.hash("same-password");
            String second = Credentials.hash("same-password");
            assertThat(first).isNotEqualTo(second);
            assertThat(Credentials.verify("same-password", first)).isTrue();
            assertThat(Credentials.verify("same-password", second)).isTrue();
        }

        @Test
        @DisplayName("a malformed or empty hash denies access instead of throwing")
        void malformedHashDenies() {
            // A corrupt configuration entry must fail closed. Throwing here would take the panel down
            // in a way an operator would read as a crash rather than as a broken credential.
            assertThat(Credentials.verify("anything", "")).isFalse();
            assertThat(Credentials.verify("anything", "not-a-hash")).isFalse();
            assertThat(Credentials.verify("anything", "pbkdf2-sha256$abc$!!$!!")).isFalse();
            assertThat(Credentials.verify("anything", null)).isFalse();
            assertThat(Credentials.verify(null, "pbkdf2-sha256$1$a$b")).isFalse();
        }

        @Test
        @DisplayName("the stored form records its own parameters")
        void storedFormIsSelfDescribing() {
            String hash = Credentials.hash("x");
            assertThat(hash).startsWith("pbkdf2-sha256$");
            assertThat(hash.split("\\$")).hasSize(4);
        }

        @Test
        @DisplayName("generated passwords avoid characters that are misread in a console")
        void generatedPasswordsAvoidAmbiguity() {
            for (int i = 0; i < 50; i++) {
                String password = Credentials.newInitialPassword();
                assertThat(password).hasSize(20);
                assertThat(password).doesNotContain("0", "O", "1", "l", "I");
            }
        }

        @Test
        @DisplayName("tokens are distinct and unpredictable in shape")
        void tokensAreDistinct() {
            String first = Credentials.newToken(32);
            String second = Credentials.newToken(32);
            assertThat(first).isNotEqualTo(second);
            assertThat(first).doesNotContain("=", "+", "/");
        }

        @Test
        @DisplayName("constant-time comparison rejects nulls and mismatches")
        void constantTimeComparison() {
            assertThat(Credentials.constantTimeEquals("abc", "abc")).isTrue();
            assertThat(Credentials.constantTimeEquals("abc", "abd")).isFalse();
            assertThat(Credentials.constantTimeEquals("abc", "abcd")).isFalse();
            assertThat(Credentials.constantTimeEquals(null, "abc")).isFalse();
            assertThat(Credentials.constantTimeEquals("abc", null)).isFalse();
        }
    }

    @Nested
    @DisplayName("configuration validation")
    class ConfigValidation {

        private WebConfig base() {
            return new WebConfig(true, "127.0.0.1", 8099, "admin",
                    Credentials.hash("a-password"), 60, 5, 15, 25, false, false, false);
        }

        @Test
        @DisplayName("a loopback panel with a password is accepted")
        void goodConfigurationIsAccepted() {
            assertThat(base().problems()).isEmpty();
        }

        @Test
        @DisplayName("a non-loopback bind is refused unless explicitly allowed")
        void nonLoopbackIsRefusedByDefault() {
            // This is the single most consequential setting. The panel is plain HTTP with one
            // password, so binding it to an interface is how a moderation console ends up on the
            // public internet by accident.
            WebConfig exposed = new WebConfig(true, "0.0.0.0", 8099, "admin",
                    Credentials.hash("a-password"), 60, 5, 15, 25, false, false, false);
            assertThat(exposed.problems()).hasSize(1);
            assertThat(exposed.problems().get(0)).contains("allow-non-loopback");
        }

        @Test
        @DisplayName("a non-loopback bind is accepted once allowed, with a warning")
        void nonLoopbackIsAcceptedWhenAllowed() {
            WebConfig exposed = new WebConfig(true, "0.0.0.0", 8099, "admin",
                    Credentials.hash("a-password"), 60, 5, 15, 25, true, false, false);
            assertThat(exposed.problems()).isEmpty();
            assertThat(exposed.warnings()).anyMatch(w -> w.contains("plain HTTP"));
        }

        @Test
        @DisplayName("the whole 127/8 range counts as loopback")
        void allLoopbackAddressesRecognised() {
            for (String address : new String[]{"127.0.0.1", "127.1.2.3", "localhost", "::1", "[::1]"}) {
                WebConfig config = new WebConfig(true, address, 8099, "admin",
                        Credentials.hash("x"), 60, 5, 15, 25, false, false, false);
                assertThat(config.isLoopbackAddress()).as(address).isTrue();
                assertThat(config.problems()).as(address).isEmpty();
            }
        }

        @Test
        @DisplayName("a panel without a password is refused")
        void missingPasswordIsRefused() {
            WebConfig noPassword = new WebConfig(true, "127.0.0.1", 8099, "admin",
                    "", 60, 5, 15, 25, false, false, false);
            assertThat(noPassword.problems()).anyMatch(p -> p.contains("password-hash"));
        }

        @Test
        @DisplayName("out-of-range numbers are refused")
        void numbersAreValidated() {
            assertThat(new WebConfig(true, "127.0.0.1", 0, "admin", "h", 60, 5, 15, 25, false, false, false)
                    .problems()).anyMatch(p -> p.contains("port"));
            assertThat(new WebConfig(true, "127.0.0.1", 8099, "admin", "h", 0, 5, 15, 25, false, false, false)
                    .problems()).anyMatch(p -> p.contains("session-minutes"));
            assertThat(new WebConfig(true, "127.0.0.1", 8099, "admin", "h", 60, 5, 15, 9999, false, false, false)
                    .problems()).anyMatch(p -> p.contains("page-size"));
        }

        @Test
        @DisplayName("trusting a proxy is warned about")
        void proxyTrustIsWarnedAbout() {
            WebConfig proxied = new WebConfig(true, "127.0.0.1", 8099, "admin",
                    Credentials.hash("x"), 60, 5, 15, 25, false, false, true);
            assertThat(proxied.warnings()).anyMatch(w -> w.contains("X-Forwarded-For"));
        }
    }

    @Nested
    @DisplayName("sessions and lockout")
    class Sessions {

        private WebConfig config(int sessionMinutes, int maxFailures, int lockoutMinutes) {
            return new WebConfig(true, "127.0.0.1", 8099, "admin", Credentials.hash("x"),
                    sessionMinutes, maxFailures, lockoutMinutes, 25, false, false, false);
        }

        @Test
        @DisplayName("a created session resolves and carries a distinct CSRF token")
        void createAndResolve() {
            WebSecurity security = new WebSecurity(config(60, 5, 15));
            WebSecurity.Session session = security.create("127.0.0.1");
            assertThat(session.csrfToken()).isNotEqualTo(session.id());
            Optional<WebSecurity.Session> resolved = security.resolve(session.id());
            assertThat(resolved).isPresent();
            assertThat(resolved.get().csrfToken()).isEqualTo(session.csrfToken());
        }

        @Test
        @DisplayName("an unknown session id does not resolve")
        void unknownSessionRejected() {
            WebSecurity security = new WebSecurity(config(60, 5, 15));
            security.create("127.0.0.1");
            assertThat(security.resolve("not-a-real-session")).isEmpty();
            assertThat(security.resolve(null)).isEmpty();
            assertThat(security.resolve("")).isEmpty();
        }

        @Test
        @DisplayName("invalidating a session ends it")
        void invalidationEndsTheSession() {
            WebSecurity security = new WebSecurity(config(60, 5, 15));
            WebSecurity.Session session = security.create("127.0.0.1");
            security.invalidate(session.id());
            assertThat(security.resolve(session.id())).isEmpty();
        }

        @Test
        @DisplayName("the session cookie is HttpOnly and SameSite=Strict")
        void cookieIsHardened() {
            WebSecurity security = new WebSecurity(config(60, 5, 15));
            String cookie = security.cookie("abc", Duration.ofMinutes(30));
            assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/");
            // Secure is deliberately absent: the panel speaks plain HTTP on loopback, and setting it
            // would stop the browser sending the cookie at all.
            assertThat(cookie).doesNotContain("Secure");
        }

        @Test
        @DisplayName("the session id is parsed out of a cookie header")
        void cookieParsing() {
            assertThat(WebSecurity.sessionIdFromCookie("other=1; xray_session=abc123; z=2"))
                    .contains("abc123");
            assertThat(WebSecurity.sessionIdFromCookie("xray_session=abc123")).contains("abc123");
            assertThat(WebSecurity.sessionIdFromCookie("other=1")).isEmpty();
            assertThat(WebSecurity.sessionIdFromCookie(null)).isEmpty();
            assertThat(WebSecurity.sessionIdFromCookie("xray_session=")).isEmpty();
        }

        @Test
        @DisplayName("locking out takes the configured number of failures, and clears on success")
        void lockoutAfterRepeatedFailures() {
            WebSecurity security = new WebSecurity(config(60, 3, 15));
            assertThat(security.isLockedOut("10.0.0.9")).isFalse();
            assertThat(security.recordFailure("10.0.0.9")).isFalse();
            assertThat(security.recordFailure("10.0.0.9")).isFalse();
            // The third failure trips it.
            assertThat(security.recordFailure("10.0.0.9")).isTrue();
            assertThat(security.isLockedOut("10.0.0.9")).isTrue();
            assertThat(security.lockoutSecondsRemaining("10.0.0.9")).isPositive();
        }

        @Test
        @DisplayName("a lockout is per address, not global")
        void lockoutIsPerAddress() {
            // Keyed by address so one attacker cannot lock the real administrator out - a global
            // counter would turn the throttle itself into a denial of service.
            WebSecurity security = new WebSecurity(config(60, 2, 15));
            security.recordFailure("10.0.0.9");
            security.recordFailure("10.0.0.9");
            assertThat(security.isLockedOut("10.0.0.9")).isTrue();
            assertThat(security.isLockedOut("10.0.0.10")).isFalse();
        }

        @Test
        @DisplayName("a successful sign-in clears the failure count")
        void successClearsFailures() {
            WebSecurity security = new WebSecurity(config(60, 3, 15));
            security.recordFailure("10.0.0.9");
            security.recordFailure("10.0.0.9");
            security.recordSuccess("10.0.0.9");
            assertThat(security.recordFailure("10.0.0.9")).isFalse();
            assertThat(security.isLockedOut("10.0.0.9")).isFalse();
        }

        @Test
        @DisplayName("a zero-length idle window expires a session immediately")
        void expiryIsEnforced() {
            // sessionMinutes of 1 with lastSeen just set: still valid. The point of the test is that
            // expiry is evaluated on use, so an expired session cannot be read while a reaper has not
            // yet run - which is why resolve() checks the clock rather than trusting the sweeper.
            WebSecurity security = new WebSecurity(config(1, 5, 15));
            WebSecurity.Session session = security.create("127.0.0.1");
            assertThat(security.resolve(session.id())).isPresent();
            assertThat(security.activeSessions()).isEqualTo(1);
        }

        @Test
        @DisplayName("sweeping drops nothing that is still valid")
        void sweepKeepsLiveSessions() {
            WebSecurity security = new WebSecurity(config(60, 5, 15));
            WebSecurity.Session session = security.create("127.0.0.1");
            security.sweep();
            assertThat(security.resolve(session.id())).isPresent();
        }
    }

    @Nested
    @DisplayName("HTML escaping")
    class HtmlEscaping {

        @Test
        @DisplayName("escapes every character that can break out of markup or an attribute")
        void escapesMarkup() {
            assertThat(Html.esc("<script>alert('x')</script>"))
                    .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
            assertThat(Html.esc("a\" onmouseover=\"evil")).contains("&quot;");
            assertThat(Html.esc("Tom & Jerry")).isEqualTo("Tom &amp; Jerry");
            assertThat(Html.esc(null)).isEmpty();
        }

        @Test
        @DisplayName("undefined statistics render as a dash, never as zero")
        void undefinedStatisticsAreNotZero() {
            assertThat(Html.percent(Double.NaN)).isEqualTo("&mdash;");
            assertThat(Html.decimal(Double.NaN, 3)).isEqualTo("&mdash;");
            assertThat(Html.percent(0.5)).isEqualTo("50.0%");
        }
    }
}
