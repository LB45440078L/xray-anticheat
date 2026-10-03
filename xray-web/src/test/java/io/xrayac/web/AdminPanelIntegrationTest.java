package io.xrayac.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.ModeratorActionRepository;
import io.xrayac.persistence.ConnectionProvider;
import io.xrayac.persistence.DatabaseConfig;
import io.xrayac.persistence.HikariConnectionProvider;
import io.xrayac.persistence.jdbc.JdbcBanWaveRepository;
import io.xrayac.persistence.jdbc.JdbcMiningEventRepository;
import io.xrayac.persistence.jdbc.JdbcModeratorActionRepository;
import io.xrayac.persistence.jdbc.JdbcOreDiscoveryRepository;
import io.xrayac.persistence.jdbc.JdbcPlayerRepository;
import io.xrayac.persistence.jdbc.JdbcSuspicionRepository;
import io.xrayac.persistence.jdbc.JdbcWorldModificationRepository;
import io.xrayac.persistence.migration.MigrationRunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of the administration panel: a real HTTP server, over a real socket, reading a real
 * SQLite database.
 *
 * <p>This is the test that matters for this feature, because almost everything the panel is supposed to
 * guarantee is a property of the request pipeline rather than of a method - that an unauthenticated
 * request cannot read player data, that a request without a CSRF token cannot change anything, that the
 * security headers are on every response, that the cookie cannot be read by script. None of that is
 * observable by calling a method directly.
 *
 * <p>The port is bound as {@code 0}, so the OS picks a free one and the suite can run alongside anything
 * else without a fixed port conflict.
 */
@DisplayName("admin panel over HTTP, against SQLite")
class AdminPanelIntegrationTest {

    private static final String USERNAME = "test-admin";
    private static final String PASSWORD = "a-test-password";

    private static final WorldId OVERWORLD = WorldId.of("survival#minecraft:overworld");
    private static final PlayerRef STEVE = PlayerRef.of(UUID.randomUUID(), "Steve");
    /**
     * Seeded as recent, not as a fixed past instant.
     *
     * <p>The panel reads bounded windows - discoveries and mining over a configurable period - so data
     * timestamped far in the past is correctly excluded and the pages were rendering empty for a
     * reason that had nothing to do with the code under test.
     */
    private static final Instant NOW = Instant.now().minus(Duration.ofHours(6));

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    /**
     * A request that never gets an answer must fail the test, not hang the suite.
     *
     * <p>Without this the suite blocks indefinitely with no diagnosis. That is not hypothetical: the
     * first time these tests ran, the server bound its port but never dispatched, so every request
     * connected and waited forever and the build hung with no output at all.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private ConnectionProvider provider;
    private JdbcPlayerRepository players;
    private JdbcOreDiscoveryRepository discoveries;
    private JdbcSuspicionRepository suspicion;
    private JdbcModeratorActionRepository actions;
    private AdminWebServer server;
    private RecordingActions moderation;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        provider = new HikariConnectionProvider(
                DatabaseConfig.sqlite(tempDir.resolve("panel-test.db").toString()));
        new MigrationRunner(provider).migrate();

        players = new JdbcPlayerRepository(provider);
        discoveries = new JdbcOreDiscoveryRepository(provider);
        suspicion = new JdbcSuspicionRepository(provider);
        actions = new JdbcModeratorActionRepository(provider);

        seedData();

        WebData data = new WebData(new WebData.Repositories(
                players,
                suspicion,
                discoveries,
                actions,
                new JdbcBanWaveRepository(provider),
                new JdbcMiningEventRepository(provider, 50),
                new JdbcWorldModificationRepository(provider, 50)),
                "SQLITE", 1, AdminWebServer.PANEL_ACTOR);

        WebConfig config = new WebConfig(true, "127.0.0.1", 0, USERNAME,
                Credentials.hash(PASSWORD), 60, 3, 15, 25, false, false, false);

        moderation = new RecordingActions();
        server = new AdminWebServer(config, data, moderation);
        server.start();
        base = "http://127.0.0.1:" + server.port();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (provider != null) {
            provider.close();
        }
    }

    private void seedData() {
        players.upsert(STEVE, NOW);
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session-1",
                new OreDiscovery("minecraft:diamond_ore", BlockPos.of(10, -59, 10), NOW,
                        ExposureState.HIDDEN, 4, 4, 0, 120.0, 40.0,
                        new TrajectoryAnalysis.Approach(true, 40.0, 12.5, 9.0, 35.0), 1.0));
        suspicion.save(new SuspicionSnapshot(
                STEVE, OVERWORLD, NOW,
                0.0, 2.5, 2.5, 0.92, 0.8, 1.0, 40, 3,
                EvidenceStrength.STRONG, List.of()));
    }

    // -------------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(REQUEST_TIMEOUT)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithCookie(String path, String cookie)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Cookie", cookie)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Signs in and returns the session cookie. */
    private String signIn() throws IOException, InterruptedException {
        HttpResponse<String> response = post("/login",
                "username=" + USERNAME + "&password=" + PASSWORD, null);
        assertThat(response.statusCode()).isEqualTo(303);
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static String csrfOf(String html) {
        Matcher matcher = CSRF.matcher(html);
        assertThat(matcher.find()).as("the page should carry a CSRF token").isTrue();
        return matcher.group(1);
    }

    // -------------------------------------------------------------------------------------------
    // tests
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the login page is reachable without a session")
    void loginPageIsPublic() throws Exception {
        HttpResponse<String> response = get("/login");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Sign in");
        // The login page must not leak whether a username exists, and must not carry data.
        assertThat(response.body()).doesNotContain("Steve");
    }

    @Test
    @DisplayName("every response carries the hardening headers")
    void securityHeadersAreAlwaysPresent() throws Exception {
        HttpResponse<String> response = get("/login");
        assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
        String csp = response.headers().firstValue("Content-Security-Policy").orElseThrow();
        // No inline script and no external origin: an injected <script> cannot run, and the panel
        // cannot be made to fetch from or post to a third party.
        assertThat(csp).contains("default-src 'none'").contains("script-src 'self'");
        assertThat(csp).doesNotContain("unsafe-inline");
        // Note the orElseThrow(): Optional.contains() tests equality, not a substring, so asserting
        // on the optional directly would compare "no-store" against the full header value and fail
        // while the header was in fact correct.
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow()).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options").orElseThrow()).contains("DENY");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    @Test
    @DisplayName("an unauthenticated page request is redirected to the login page")
    void unauthenticatedIsRedirected() throws Exception {
        HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).contains("/login");
    }

    @Test
    @DisplayName("an unauthenticated POST is refused rather than redirected")
    void unauthenticatedPostIsRefused() throws Exception {
        // Answering a state-changing request with a redirect to a login page is how a caller comes to
        // believe it succeeded. This one is an explicit 401.
        HttpResponse<String> response = post("/players/" + STEVE.id() + "/action", "action=ban", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(actions.forPlayer(STEVE.id(), 10)).isEmpty();
    }

    @Test
    @DisplayName("player data is never served to an unauthenticated caller")
    void playerDataIsNotExposed() throws Exception {
        assertThat(get("/players").body()).doesNotContain("Steve");
        assertThat(get("/players/" + STEVE.id()).body()).doesNotContain("Steve");
        // The JSON endpoint exists for scripting, so it is checked too - it is the easiest place for
        // an authentication check to be forgotten.
        assertThat(get("/api/stats").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a wrong password is refused and does not create a session")
    void wrongPasswordIsRefused() throws Exception {
        HttpResponse<String> response = post("/login",
                "username=" + USERNAME + "&password=wrong", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(server.activeSessions()).isZero();
    }

    @Test
    @DisplayName("a wrong username is refused with the same message as a wrong password")
    void wrongUsernameLooksLikeWrongPassword() throws Exception {
        String badUser = post("/login", "username=nobody&password=" + PASSWORD, null).body();
        String badPassword = post("/login", "username=" + USERNAME + "&password=nope", null).body();
        // Identical wording: distinguishing the two would confirm a valid username to a guesser.
        assertThat(badUser).contains("Incorrect username or password");
        assertThat(badPassword).contains("Incorrect username or password");
    }

    @Test
    @DisplayName("repeated failures lock the address out")
    void repeatedFailuresLockOut() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            post("/login", "username=" + USERNAME + "&password=wrong", null);
        }
        HttpResponse<String> locked = post("/login",
                "username=" + USERNAME + "&password=" + PASSWORD, null);
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.body()).contains("Too many failed attempts");
    }

    @Test
    @DisplayName("signing in works and gives a session cookie that is HttpOnly and SameSite=Strict")
    void signInSucceeds() throws Exception {
        HttpResponse<String> response = post("/login",
                "username=" + USERNAME + "&password=" + PASSWORD, null);
        assertThat(response.statusCode()).isEqualTo(303);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(server.activeSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("an authenticated dashboard renders the stored player")
    void dashboardRendersData() throws Exception {
        String cookie = signIn();
        HttpResponse<String> response = getWithCookie("/", cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Overview");
        // The player page is where the evidence actually shows.
        HttpResponse<String> player = getWithCookie("/players/" + STEVE.id(), cookie);
        assertThat(player.statusCode()).isEqualTo(200);
        assertThat(player.body()).contains("Steve");
        assertThat(player.body()).contains("STRONG");
        assertThat(player.body()).contains("minecraft:diamond_ore");
    }

    @Test
    @DisplayName("the players page also renders the stored player")
    void playersPageRenders() throws Exception {
        String cookie = signIn();
        HttpResponse<String> response = getWithCookie("/players", cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Steve");
    }

    @Test
    @DisplayName("a POST without a CSRF token is refused and changes nothing")
    void csrfIsEnforced() throws Exception {
        String cookie = signIn();
        HttpResponse<String> response = post("/players/" + STEVE.id() + "/action",
                "action=note&reason=should+not+be+recorded", cookie);
        assertThat(response.statusCode()).isEqualTo(403);
        // The important half of this assertion: nothing was written.
        assertThat(actions.forPlayer(STEVE.id(), 10)).isEmpty();
    }

    @Test
    @DisplayName("a POST with a forged CSRF token is refused")
    void forgedCsrfIsRefused() throws Exception {
        String cookie = signIn();
        HttpResponse<String> response = post("/players/" + STEVE.id() + "/action",
                "csrf=not-the-real-token&action=note&reason=no", cookie);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(actions.forPlayer(STEVE.id(), 10)).isEmpty();
    }

    @Test
    @DisplayName("a POST with the real CSRF token is accepted and writes an audit entry")
    void csrfAllowsTheAction() throws Exception {
        String cookie = signIn();
        String page = getWithCookie("/players/" + STEVE.id(), cookie).body();
        String csrf = csrfOf(page);

        HttpResponse<String> response = post("/players/" + STEVE.id() + "/action",
                "csrf=" + csrf + "&action=note&reason=watched+this+one", cookie);
        assertThat(response.statusCode()).isEqualTo(200);

        List<ModeratorActionRepository.StoredAction> recorded = actions.forPlayer(STEVE.id(), 10);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).action()).isEqualTo("note");
        assertThat(recorded.get(0).note()).isEqualTo("watched this one");
        // Attributed to the panel, never to a real player.
        assertThat(recorded.get(0).moderatorId()).isEqualTo(AdminWebServer.PANEL_ACTOR);
    }

    @Test
    @DisplayName("a kick for an offline player reports that nothing happened")
    void kickForOfflinePlayerReportsHonestly() throws Exception {
        String cookie = signIn();
        String csrf = csrfOf(getWithCookie("/players/" + STEVE.id(), cookie).body());

        // The recording implementation reports false, exactly as the real one does for a player who
        // is not connected.
        HttpResponse<String> response = post("/players/" + STEVE.id() + "/action",
                "csrf=" + csrf + "&action=kick&reason=away", cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("not online");
        // And the audit trail records the attempt as unsuccessful rather than as a kick.
        List<ModeratorActionRepository.StoredAction> recorded = actions.forPlayer(STEVE.id(), 10);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).note()).contains("not online");
        // The attempt was made and refused; what must not happen is the panel claiming a kick.
        assertThat(moderation.kicks).containsExactly(STEVE.id());
    }

    @Test
    @DisplayName("a ban is applied and recorded when the server accepts it")
    void banIsRecorded() throws Exception {
        moderation.banSucceeds = true;
        String cookie = signIn();
        String csrf = csrfOf(getWithCookie("/players/" + STEVE.id(), cookie).body());

        post("/players/" + STEVE.id() + "/action", "csrf=" + csrf + "&action=ban&reason=ore+vision", cookie);

        assertThat(moderation.bans).containsExactly(STEVE.id());
        assertThat(actions.forPlayer(STEVE.id(), 10))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.action()).isEqualTo("ban");
                    assertThat(action.note()).isEqualTo("ore vision");
                });
    }

    @Test
    @DisplayName("read-only mode refuses moderation but still records nothing as done")
    void readOnlyModeRefusesModeration() throws Exception {
        // Rebuild the server in read-only mode against the same database.
        server.stop();
        WebData data = new WebData(new WebData.Repositories(
                players, suspicion, discoveries, actions,
                new JdbcBanWaveRepository(provider),
                new JdbcMiningEventRepository(provider, 50),
                new JdbcWorldModificationRepository(provider, 50)),
                "SQLITE", 1, AdminWebServer.PANEL_ACTOR);
        WebConfig readOnly = new WebConfig(true, "127.0.0.1", 0, USERNAME,
                Credentials.hash(PASSWORD), 60, 3, 15, 25, false, true, false);
        server = new AdminWebServer(readOnly, data, moderation);
        server.start();
        base = "http://127.0.0.1:" + server.port();

        String cookie = signIn();
        String page = getWithCookie("/players/" + STEVE.id(), cookie).body();
        assertThat(page).contains("read-only mode");
        String csrf = csrfOf(page);

        post("/players/" + STEVE.id() + "/action", "csrf=" + csrf + "&action=kick&reason=x", cookie);
        assertThat(moderation.kicks).isEmpty();
        assertThat(actions.forPlayer(STEVE.id(), 10)).isEmpty();
    }

    @Test
    @DisplayName("signing out invalidates the session")
    void signOutEndsTheSession() throws Exception {
        String cookie = signIn();
        String csrf = csrfOf(getWithCookie("/", cookie).body());
        HttpResponse<String> response = post("/logout", "csrf=" + csrf, cookie);
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(getWithCookie("/", cookie).statusCode()).isEqualTo(303);
    }

    @Test
    @DisplayName("the static assets are served from the jar and are not filesystem paths")
    void assetsAreServedFromResources() throws Exception {
        HttpResponse<String> css = get("/assets/app.css");
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.headers().firstValue("Content-Type").orElseThrow()).contains("text/css");
        assertThat(css.body()).contains(".card");

        HttpResponse<String> js = get("/assets/app.js");
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.headers().firstValue("Content-Type").orElseThrow()).contains("javascript");
    }

    @Test
    @DisplayName("a traversal attempt against the asset path finds nothing")
    void traversalIsNotPossible() throws Exception {
        // Assets come from a fixed map, not from the filesystem, so there is no path to walk.
        for (String attempt : new String[]{
                "/assets/../config.yml",
                "/assets/%2e%2e/config.yml",
                "/assets/../../../../etc/passwd"}) {
            HttpResponse<String> response = get(attempt);
            assertThat(response.statusCode()).as(attempt).isNotEqualTo(200);
        }
    }

    @Test
    @DisplayName("an unsupported method is refused")
    void unsupportedMethodsRefused() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/")).method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow").orElseThrow()).contains("GET");
    }

    @Test
    @DisplayName("a health probe answers without a session and says nothing about the data")
    void healthProbeIsMinimal() throws Exception {
        HttpResponse<String> response = get("/api/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{}");
    }

    @Test
    @DisplayName("an unknown player id is a 404, not a crash")
    void unknownPlayerIsNotFound() throws Exception {
        String cookie = signIn();
        assertThat(getWithCookie("/players/" + UUID.randomUUID(), cookie).statusCode()).isEqualTo(404);
        assertThat(getWithCookie("/players/not-a-uuid", cookie).statusCode()).isEqualTo(404);
    }

    // -------------------------------------------------------------------------------------------

    /** Records what it was asked to do, and reports success or failure on command. */
    private static final class RecordingActions implements ModerationActions {
        private final List<UUID> kicks = new ArrayList<>();
        private final List<UUID> bans = new ArrayList<>();
        private final List<UUID> unbans = new ArrayList<>();
        private final List<UUID> messages = new ArrayList<>();
        private boolean banSucceeds;

        @Override
        public List<OnlinePlayer> online() {
            return List.of();
        }

        @Override
        public boolean kick(UUID playerId, String reason) {
            kicks.add(playerId);
            return false;
        }

        @Override
        public boolean ban(UUID playerId, String reason) {
            bans.add(playerId);
            return banSucceeds;
        }

        @Override
        public boolean unban(UUID playerId) {
            unbans.add(playerId);
            return false;
        }

        @Override
        public boolean message(UUID playerId, String text) {
            messages.add(playerId);
            return false;
        }
    }
}
