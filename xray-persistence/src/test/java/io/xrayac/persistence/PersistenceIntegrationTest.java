package io.xrayac.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.persistence.jdbc.JdbcBanWaveRepository;
import io.xrayac.persistence.jdbc.JdbcPlayerRepository;
import io.xrayac.persistence.jdbc.JdbcSuspicionRepository;
import io.xrayac.persistence.jdbc.JdbcWorldModificationRepository;
import io.xrayac.persistence.migration.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests running against a real embedded SQLite database.
 *
 * <p>These exist because compiling is not the same as working. The unit tests in {@code xray-core}
 * exercise the mathematics in isolation; they would happily pass while the schema contained a
 * syntax error, or while a repository's column order did not match the table it targets. Every test
 * here runs against an actual database file created by the real migration runner, so a broken
 * migration, a mis-ordered bind parameter or a type mismatch fails the build.
 *
 * <p>SQLite is used because it needs no server and is the default for small installations. MariaDB
 * and PostgreSQL are exercised by the same code paths — the schema is deliberately dialect-neutral —
 * but verifying them requires a live server and is left to deployment.
 */
class PersistenceIntegrationTest {

    private static final WorldId WORLD = WorldId.of("survival#minecraft:overworld");
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    @TempDir
    Path tempDir;

    private ConnectionProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        Path databaseFile = tempDir.resolve("xray-test.db");
        provider = new HikariConnectionProvider(DatabaseConfig.sqlite(databaseFile.toString()));
        MigrationRunner runner = new MigrationRunner(provider);
        runner.migrate();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    @DisplayName("migrations apply once and are recorded in the ledger")
    void migrationsApplyOnce() throws SQLException {
        MigrationRunner runner = new MigrationRunner(provider);
        MigrationRunner.MigrationReport secondRun = runner.migrate();

        // Running again must be a no-op: migrations are applied exactly once, keyed by version.
        assertThat(secondRun.changedAnything()).isFalse();
        assertThat(secondRun.totalMigrations()).isEqualTo(1);

        try (Connection connection = provider.acquire();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM schema_migrations");
             ResultSet rows = select.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("every table in the schema was actually created")
    void schemaIsComplete() throws SQLException {
        List<String> expected = List.of("players", "player_sessions", "world_modifications",
                "mining_events", "ore_discoveries", "ore_veins", "suspicion_snapshots",
                "evidence_events", "ban_wave_candidates", "ban_waves", "moderator_actions",
                "world_analysis", "plugin_metadata", "schema_migrations");

        for (String table : expected) {
            try (Connection connection = provider.acquire();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT COUNT(*) FROM " + table);
                 ResultSet rows = select.executeQuery()) {
                assertThat(rows.next())
                        .as("table %s should be queryable", table)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("a player round-trips, and a second upsert updates rather than duplicates")
    void playerRoundTrip() {
        JdbcPlayerRepository repository = new JdbcPlayerRepository(provider);
        UUID id = UUID.randomUUID();

        repository.upsert(PlayerRef.of(id, "Steve"), NOW);
        repository.upsert(PlayerRef.of(id, "SteveRenamed"), NOW.plusSeconds(60));

        Optional<PlayerRepositoryStored> found = repository.find(id).map(s -> new PlayerRepositoryStored(
                s.player().name(), s.firstSeen(), s.lastSeen()));
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("SteveRenamed");
        // The first-seen time must survive the update.
        assertThat(found.get().firstSeen()).isEqualTo(NOW);
        assertThat(found.get().lastSeen()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.mostRecentlySeen(10)).hasSize(1);
    }

    @Test
    @DisplayName("the excavation ledger records provenance and reads it back by coordinate")
    void worldModificationRoundTrip() {
        JdbcWorldModificationRepository repository =
                new JdbcWorldModificationRepository(provider, 500);
        UUID digger = UUID.randomUUID();
        BlockPos pos = BlockPos.of(100, -59, 200);

        repository.record(WORLD, pos, MiningOrigin.PLAYER_CREATED, digger, NOW);
        repository.record(WORLD, pos.add(1, 0, 0), MiningOrigin.NATURAL_TERRAIN, null, NOW);

        assertThat(repository.originAt(WORLD, pos)).contains(MiningOrigin.PLAYER_CREATED);
        assertThat(repository.removedAtEpochMillis(WORLD, pos)).isEqualTo(NOW.toEpochMilli());
        assertThat(repository.originAt(WORLD, pos.add(1, 0, 0))).contains(MiningOrigin.NATURAL_TERRAIN);
        // An unrecorded position returns empty, not a fabricated origin.
        assertThat(repository.originAt(WORLD, BlockPos.of(9999, 9999, 9999))).isEmpty();
        assertThat(repository.removedAtEpochMillis(WORLD, BlockPos.of(9999, 9999, 9999))).isEqualTo(-1L);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a suspicion snapshot is stored atomically with all of its evidence")
    void suspicionRoundTrip() {
        JdbcPlayerRepository players = new JdbcPlayerRepository(provider);
        JdbcSuspicionRepository repository = new JdbcSuspicionRepository(provider);

        PlayerRef player = PlayerRef.of(UUID.randomUUID(), "Subject");
        players.upsert(player, NOW);

        SuspicionSnapshot snapshot = new SuspicionSnapshot(player, WORLD, NOW,
                -3.9, 2.5, 2.5, 0.92, 0.8, 0.97, 40, 3, EvidenceStrength.STRONG,
                List.of(
                        new EvidenceContribution("hidden-discovery-rate", "discovery-rate",
                                2.0, 40, 0.8, "rate exceeded the legitimate yield", Map.of()),
                        new EvidenceContribution("ore-targeting", "targeting",
                                1.25, 40, 0.75, "approaches were consistently aligned", Map.of())));

        repository.save(snapshot);

        Optional<SuspicionSnapshot> loaded = repository.latest(player.id());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().evidenceStrength()).isEqualTo(EvidenceStrength.STRONG);
        assertThat(loaded.get().sampleSize()).isEqualTo(40);
        // The evidence rows must come back with the snapshot, or the assessment is unexplainable.
        assertThat(loaded.get().contributions()).hasSize(2);
        assertThat(loaded.get().contributions())
                .extracting(EvidenceContribution::componentId)
                .containsExactlyInAnyOrder("hidden-discovery-rate", "ore-targeting");
    }

    @Test
    @DisplayName("re-recording a candidate strengthens the peak and preserves first detection")
    void banWaveCandidateStrengthening() {
        JdbcPlayerRepository players = new JdbcPlayerRepository(provider);
        JdbcBanWaveRepository repository = new JdbcBanWaveRepository(provider);

        PlayerRef player = PlayerRef.of(UUID.randomUUID(), "RepeatOffender");
        players.upsert(player, NOW);

        // Deliberately ordered so that a naive string comparison would get it wrong: WEAK sorts
        // alphabetically after VERY_STRONG while being far weaker. A correct merge must keep the
        // stronger band and must NOT let the later, weaker record overwrite it.
        BanWaveCandidate strong = new BanWaveCandidate(player, WORLD, NOW, NOW,
                0.99, 0.95, EvidenceStrength.VERY_STRONG, 80, 4, "strong evidence");
        repository.upsertCandidate(strong);

        BanWaveCandidate laterWeaker = new BanWaveCandidate(player, WORLD,
                NOW.plusSeconds(3600), NOW.plusSeconds(3600),
                0.30, 0.40, EvidenceStrength.WEAK, 12, 2, "weaker later evidence");
        repository.upsertCandidate(laterWeaker);

        List<BanWaveCandidate> candidates = repository.candidates();
        assertThat(candidates).hasSize(1);
        BanWaveCandidate stored = candidates.getFirst();
        assertThat(stored.peakStrength()).isEqualTo(EvidenceStrength.VERY_STRONG);
        assertThat(stored.peakSuspicionScore()).isEqualTo(0.99);
        assertThat(stored.peakConfidence()).isEqualTo(0.95);
        assertThat(stored.sampleSize()).isEqualTo(80);
        // The earliest detection is preserved, so the record shows how long they have been flagged.
        assertThat(stored.firstDetected()).isEqualTo(NOW);
        assertThat(stored.lastDetected()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("records can be pruned by retention without breaking the schema")
    void retentionPruning() {
        JdbcWorldModificationRepository ledger =
                new JdbcWorldModificationRepository(provider, 500);
        ledger.record(WORLD, BlockPos.of(0, 0, 0), MiningOrigin.PLAYER_CREATED, UUID.randomUUID(), NOW);

        assertThat(ledger.deleteOlderThan(NOW.plusSeconds(1))).isEqualTo(1);
        assertThat(ledger.count()).isZero();
    }

    /** Projection used only to keep the player assertions readable. */
    private record PlayerRepositoryStored(String name, Instant firstSeen, Instant lastSeen) {
    }
}
