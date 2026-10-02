package io.xrayac.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.analysis.AnalysisHistory;
import io.xrayac.core.analysis.HistoryHydrator;
import io.xrayac.core.analysis.HistoryParameters;
import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.MiningEventRepository;
import io.xrayac.core.repository.OreDiscoveryRepository;
import io.xrayac.persistence.jdbc.JdbcMiningEventRepository;
import io.xrayac.persistence.jdbc.JdbcOreDiscoveryRepository;
import io.xrayac.persistence.migration.MigrationRunner;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of history hydration against a real embedded SQLite database.
 *
 * <p>The unit tests in {@code xray-core} run the hydrator against in-memory fakes, which proves the
 * merge logic but proves nothing about the SQL underneath it: a wrong column order, a lost type, a
 * dropped alignment angle or an ordering that differs from what the fake emulated would all pass there
 * and fail here. This test writes through the real repositories and reads back through the real
 * hydrator, so the reconstruction is checked against the schema that actually ships.
 */
@DisplayName("history hydration over SQLite")
class HistoryHydrationIntegrationTest {

    private static final WorldId OVERWORLD = WorldId.of("survival#minecraft:overworld");
    private static final WorldId NETHER = WorldId.of("survival#minecraft:the_nether");
    private static final PlayerRef STEVE = PlayerRef.of(UUID.randomUUID(), "Steve");
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    @TempDir
    Path tempDir;

    private ConnectionProvider provider;
    private OreDiscoveryRepository discoveries;
    private MiningEventRepository miningEvents;
    private HistoryHydrator hydrator;

    @BeforeEach
    void setUp() throws SQLException {
        Path databaseFile = tempDir.resolve("xray-history-test.db");
        provider = new HikariConnectionProvider(DatabaseConfig.sqlite(databaseFile.toString()));
        new MigrationRunner(provider).migrate();
        discoveries = new JdbcOreDiscoveryRepository(provider);
        miningEvents = new JdbcMiningEventRepository(provider, 100);
        hydrator = new HistoryHydrator(discoveries, miningEvents);
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    private static HistoryParameters parameters() {
        return new HistoryParameters(true, Duration.ofDays(90), 1000, 10000, Duration.ofMinutes(15));
    }

    private static OreDiscovery discovery(String oreId, int x, Instant time, ExposureState state,
                                          TrajectoryAnalysis.Approach approach, double distanceSince) {
        return new OreDiscovery(oreId, BlockPos.of(x, -59, 0), time, state, 4,
                state == ExposureState.HIDDEN ? 4 : 0, state == ExposureState.HIDDEN ? 0 : 4,
                75, distanceSince, approach, 1.0);
    }

    private static Observation.Mining mining(WorldId world, int x, Instant time) {
        return new Observation.Mining(STEVE, world, time.toEpochMilli(), time,
                BlockPos.of(x, -59, 0), "minecraft:deepslate_diamond_ore", MiningOrigin.PLAYER_CREATED);
    }

    @Test
    @DisplayName("discoveries survive the round trip in oldest-first order")
    void discoveriesRoundTripOldestFirst() {
        Instant earliest = NOW.minus(Duration.ofDays(10));
        Instant middle = NOW.minus(Duration.ofDays(5));
        Instant latest = NOW.minus(Duration.ofDays(1));
        // Written newest-first on purpose: the repository's query returns newest-first, and the hydrator
        // is responsible for reversing it.
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session", hidden(3, latest));
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session", hidden(2, middle));
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session", hidden(1, earliest));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters());

        assertThat(history.discoveries()).extracting(OreDiscovery::time)
                .containsExactly(earliest, middle, latest);
        assertThat(history.discoveries()).extracting(OreDiscovery::discoveryBlock)
                .containsExactly(BlockPos.of(1, -59, 0), BlockPos.of(2, -59, 0), BlockPos.of(3, -59, 0));
    }

    @Test
    @DisplayName("every field the engine reads survives the round trip, angles included")
    void discoveryFieldsSurviveTheSchema() {
        TrajectoryAnalysis.Approach approach = new TrajectoryAnalysis.Approach(true, 8.0, 12.5, 9.25, 3.0);
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session",
                discovery("diamond", 7, NOW.minus(Duration.ofDays(1)), ExposureState.HIDDEN,
                        approach, 42.5));

        OreDiscovery read = hydrator.load(STEVE, OVERWORLD, NOW, parameters()).discoveries().getFirst();

        // The targeting model consults hadData and the two angles and nothing else, so those are the ones
        // the schema has to carry. If a column were dropped or mis-ordered, this fails here rather than
        // silently weakening the signal in production.
        assertThat(read.approach().hadData()).isTrue();
        assertThat(read.approach().movementAlignmentDegrees()).isEqualTo(12.5);
        assertThat(read.approach().lookAlignmentDegrees()).isEqualTo(9.25);
        assertThat(read.discoveryExposure()).isEqualTo(ExposureState.HIDDEN);
        assertThat(read.oreId()).isEqualTo("diamond");
        assertThat(read.veinSize()).isEqualTo(4);
        assertThat(read.hiddenVeinBlocks()).isEqualTo(4);
        assertThat(read.distanceTravelledSincePrevious()).isEqualTo(42.5);
        assertThat(read.time()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("a discovery with no measurable approach stays unmeasurable")
    void absentApproachStaysAbsent() {
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session", hidden(1, NOW.minus(Duration.ofDays(1))));
        discoveries.save(STEVE.id(), OVERWORLD.key(), "session", exposedWithApproach());

        List<OreDiscovery> read = hydrator.load(STEVE, OVERWORLD, NOW, parameters()).discoveries();

        assertThat(read).hasSize(2);
        // Matched by block rather than by position: the two discoveries sit in an order the test should
        // not have to reason about, and asserting on the wrong end would look like a schema bug.
        OreDiscovery withoutApproach = read.stream()
                .filter(candidate -> candidate.discoveryBlock().x() == 1)
                .findFirst().orElseThrow();
        OreDiscovery withApproach = read.stream()
                .filter(candidate -> candidate.discoveryBlock().x() == 80)
                .findFirst().orElseThrow();

        // Fabricating approach data from nothing would manufacture targeting evidence, so absent data must
        // come back absent. This is the check that the nullable columns are written and read as NULLs
        // rather than as zero angles, which would read as a perfectly aligned approach.
        assertThat(withoutApproach.approach().hadData()).isFalse();
        assertThat(withoutApproach.measurableApproach()).isEmpty();
        assertThat(withApproach.approach().hadData()).isTrue();
        assertThat(withApproach.measurableApproach()).isPresent();
    }

    @Test
    @DisplayName("the mining path and block count are rebuilt from the stored events")
    void trajectoryIsRebuiltFromStoredEvents() {
        Instant first = NOW.minus(Duration.ofDays(3));
        Instant second = NOW.minus(Duration.ofDays(2));
        Instant third = NOW.minus(Duration.ofDays(1));
        miningEvents.saveAll(List.of(mining(OVERWORLD, 30, third), mining(OVERWORLD, 10, first),
                mining(OVERWORLD, 20, second)));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters());

        assertThat(history.blocksMined()).isEqualTo(3);
        assertThat(history.miningPath()).extracting(point -> point.position().x())
                .containsExactly(10.5, 20.5, 30.5);
        assertThat(history.earliestObservation()).contains(first);
    }

    @Test
    @DisplayName("history is scoped to one player and one world, as the schema's keys require")
    void historyIsScoped() {
        PlayerRef alex = PlayerRef.of(UUID.randomUUID(), "Alex");
        discoveries.save(STEVE.id(), OVERWORLD.key(), "s", hidden(1, NOW.minus(Duration.ofDays(1))));
        discoveries.save(STEVE.id(), NETHER.key(), "s", hidden(2, NOW.minus(Duration.ofDays(1))));
        discoveries.save(alex.id(), OVERWORLD.key(), "s", hidden(3, NOW.minus(Duration.ofDays(1))));
        miningEvents.saveAll(List.of(mining(OVERWORLD, 10, NOW.minus(Duration.ofDays(1))),
                mining(NETHER, 11, NOW.minus(Duration.ofDays(1)))));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters());

        assertThat(history.discoveries()).hasSize(1);
        assertThat(history.discoveries().getFirst().discoveryBlock()).isEqualTo(BlockPos.of(1, -59, 0));
        assertThat(history.blocksMined()).isEqualTo(1);
    }

    @Test
    @DisplayName("a whole session boundary is reconstructed: stored past merged with a live window")
    void mergeOverRealStorage() {
        Instant longAgo = NOW.minus(Duration.ofDays(12));
        for (int i = 0; i < 3; i++) {
            Instant at = longAgo.plusSeconds(i * 600L);
            discoveries.save(STEVE.id(), OVERWORLD.key(), "old-session", hidden(i, at));
            miningEvents.saveAll(List.of(mining(OVERWORLD, i, at)));
        }
        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters());

        // The live session has observed two more discoveries and mined 500 blocks since it began.
        List<OreDiscovery> session = List.of(
                hidden(50, NOW.minusSeconds(120)),
                hidden(51, NOW.minusSeconds(60)));
        PlayerAnalysisWindow live = PlayerAnalysisWindow.of(STEVE, OVERWORLD,
                NOW.minus(Duration.ofMinutes(5)), NOW, 500, 0, session,
                List.of(TrajectoryAnalysis.PathPoint.of(
                        new io.xrayac.core.geom.Vector3(50, -59, 0), NOW.minusSeconds(120))));

        PlayerAnalysisWindow merged = hydrator.merge(live, history);

        assertThat(merged.discoveries()).hasSize(5);
        assertThat(merged.blocksMined()).isEqualTo(503);
        assertThat(merged.pathPoints()).hasSize(4);
        assertThat(merged.includesHistory()).isTrue();
        assertThat(merged.liveDiscoveryIndex()).isEqualTo(3);
        assertThat(merged.windowStart()).isEqualTo(longAgo);

        // And the seam is actually dropped by the model, not merely recorded.
        long observed = merged.hiddenDiscoveryIntervals().stream()
                .filter(interval -> !interval.rightCensored())
                .count();
        assertThat(observed).isEqualTo(4);
    }

    private static OreDiscovery hidden(int x, Instant time) {
        return discovery("diamond", x, time, ExposureState.HIDDEN, TrajectoryAnalysis.Approach.noData(), 10);
    }

    private static OreDiscovery exposedWithApproach() {
        return discovery("emerald", 80, NOW.minus(Duration.ofDays(2)), ExposureState.FULLY_EXPOSED,
                new TrajectoryAnalysis.Approach(true, 6.0, 20.0, 18.0, 2.0), 15);
    }
}
