package io.xrayac.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.MiningEventRepository;
import io.xrayac.core.repository.OreDiscoveryRepository;
import io.xrayac.core.statistics.LikelihoodRatios;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for reading a player's stored past back into the analysis window.
 *
 * <p>The fakes deliberately reproduce the real queries' newest-first ordering, because the hydrator
 * has to reverse them and a fake that returned ascending rows would hide that whole class of mistake.
 */
@DisplayName("history hydration")
class HistoryHydratorTest {

    private static final PlayerRef STEVE = PlayerRef.of(UUID.randomUUID(), "Steve");
    private static final PlayerRef ALEX = PlayerRef.of(UUID.randomUUID(), "Alex");
    private static final WorldId OVERWORLD = WorldId.of("world#minecraft:overworld");
    private static final WorldId NETHER = WorldId.of("world_nether#minecraft:the_nether");
    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

    private final FakeDiscoveries discoveries = new FakeDiscoveries();
    private final FakeMining mining = new FakeMining();
    private final HistoryHydrator hydrator = new HistoryHydrator(discoveries, mining);

    private static HistoryParameters parameters(int maxDiscoveries, int maxMiningEvents) {
        return new HistoryParameters(true, Duration.ofDays(90), maxDiscoveries, maxMiningEvents,
                Duration.ofMinutes(15));
    }

    private static OreDiscovery discovery(String oreId, int x, Instant time, ExposureState state,
                                          double blocksSince, double distanceSince) {
        return new OreDiscovery(oreId, BlockPos.of(x, -59, 0), time, state, 4,
                state == ExposureState.HIDDEN ? 4 : 0, state == ExposureState.HIDDEN ? 0 : 4,
                blocksSince, distanceSince, TrajectoryAnalysis.Approach.noData(), 1.0);
    }

    private static OreDiscovery hidden(int x, Instant time, double blocksSince) {
        return discovery("diamond", x, time, ExposureState.HIDDEN, blocksSince, 10);
    }

    private static Observation.Mining mining(int x, Instant time) {
        return new Observation.Mining(STEVE, OVERWORLD, time.toEpochMilli(), time,
                BlockPos.of(x, -59, 0), "minecraft:deepslate", MiningOrigin.PLAYER_CREATED);
    }

    // ---------------------------------------------------------------------------------------
    // load
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("switched off, nothing is read at all")
    void sessionOnlyModeReadsNothing() {
        discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery(
                "d1", STEVE.id(), OVERWORLD.key(), hidden(1, NOW.minus(Duration.ofDays(1)), 50)));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, HistoryParameters.sessionOnly());

        assertThat(history.isEmpty()).isTrue();
        assertThat(discoveries.findRecentCalls).isZero();
        assertThat(mining.findRecentCalls).isZero();
    }

    @Test
    @DisplayName("the newest-first read is reversed into the oldest-first order the engine reads")
    void loadReversesStoredOrder() {
        Instant first = NOW.minus(Duration.ofDays(3));
        Instant second = NOW.minus(Duration.ofDays(2));
        Instant third = NOW.minus(Duration.ofDays(1));
        discoveries.stored.add(stored("a", third, 3));
        discoveries.stored.add(stored("b", first, 1));
        discoveries.stored.add(stored("c", second, 2));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters(100, 100));

        assertThat(history.discoveries()).extracting(OreDiscovery::time)
                .containsExactly(first, second, third);
    }

    @Test
    @DisplayName("only the requested world is read, because a window is scoped to one world")
    void loadFiltersToTheRequestedWorld() {
        discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery("o1", STEVE.id(),
                OVERWORLD.key(), hidden(1, NOW.minus(Duration.ofDays(1)), 40)));
        discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery("n1", STEVE.id(),
                NETHER.key(), hidden(2, NOW.minus(Duration.ofDays(1)), 40)));
        mining.events.add(mining(10, NOW.minus(Duration.ofDays(1))));
        mining.events.add(new Observation.Mining(STEVE, NETHER, 5L, NOW.minus(Duration.ofDays(1)),
                BlockPos.of(11, 60, 0), "minecraft:netherrack", MiningOrigin.PLAYER_CREATED));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters(100, 100));

        // Pooling the overworld with the nether would corrupt both ore distributions and both geometries.
        assertThat(history.discoveries()).hasSize(1);
        assertThat(history.discoveries().getFirst().discoveryBlock()).isEqualTo(BlockPos.of(1, -59, 0));
        assertThat(history.miningPath()).hasSize(1);
        assertThat(history.blocksMined()).isEqualTo(1);
    }

    @Test
    @DisplayName("another player's rows are never mixed in")
    void loadIsScopedToThePlayer() {
        discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery(
                "d1", ALEX.id(), OVERWORLD.key(), hidden(1, NOW.minus(Duration.ofDays(1)), 40)));

        assertThat(hydrator.load(STEVE, OVERWORLD, NOW, parameters(100, 100)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the mining path is rebuilt in time order and blocks mined is counted")
    void loadRebuildsTheTrajectory() {
        mining.events.add(mining(30, NOW.minus(Duration.ofDays(1))));
        mining.events.add(mining(10, NOW.minus(Duration.ofDays(3))));
        mining.events.add(mining(20, NOW.minus(Duration.ofDays(2))));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters(100, 100));

        assertThat(history.blocksMined()).isEqualTo(3);
        assertThat(history.miningPath()).hasSize(3);
        assertThat(history.miningPath()).extracting(point -> point.position().x())
                .containsExactly(10.5, 20.5, 30.5);
        assertThat(history.earliestObservation()).contains(NOW.minus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("historical distance is the sum of what the discoveries recorded")
    void loadSumsRecordedDistance() {
        discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery(
                "d1", STEVE.id(), OVERWORLD.key(), hidden(1, NOW.minus(Duration.ofDays(2)), 40)));
        discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery(
                "d2", STEVE.id(), OVERWORLD.key(), hidden(2, NOW.minus(Duration.ofDays(1)), 40)));

        AnalysisHistory history = hydrator.load(STEVE, OVERWORLD, NOW, parameters(100, 100));

        // Travel that produced no discovery is not stored at all, so this is a lower bound.
        assertThat(history.distanceTravelled()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("a read that hits its limit is reported as truncated")
    void loadReportsTruncation() {
        for (int i = 0; i < 5; i++) {
            discoveries.stored.add(new OreDiscoveryRepository.StoredDiscovery("d" + i, STEVE.id(),
                    OVERWORLD.key(), hidden(i, NOW.minus(Duration.ofDays(1)).plusSeconds(i), 40)));
        }

        AnalysisHistory limited = hydrator.load(STEVE, OVERWORLD, NOW, parameters(3, 100));
        AnalysisHistory roomy = hydrator.load(STEVE, OVERWORLD, NOW, parameters(100, 100));

        assertThat(limited.truncated()).isTrue();
        assertThat(roomy.truncated()).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // merge
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an empty history leaves the live window untouched")
    void mergeWithNothingReturnsTheLiveWindow() {
        PlayerAnalysisWindow live = liveWindow(List.of(hidden(1, NOW, 40)), 100, List.of());

        assertThat(hydrator.merge(live, AnalysisHistory.empty())).isSameAs(live);
    }

    @Test
    @DisplayName("effort, distance and the window start are combined across the seam")
    void mergeCombinesTheSessionWithItsPast() {
        Instant longAgo = NOW.minus(Duration.ofDays(20));
        PlayerAnalysisWindow live = liveWindow(List.of(hidden(5, NOW, 30)), 200,
                List.of(TrajectoryAnalysis.PathPoint.of(new io.xrayac.core.geom.Vector3(5, -59, 0), NOW)));
        AnalysisHistory history = new AnalysisHistory(
                List.of(hidden(1, longAgo, 60), hidden(2, longAgo.plusSeconds(600), 70)),
                900, 250,
                List.of(TrajectoryAnalysis.PathPoint.of(new io.xrayac.core.geom.Vector3(1, -59, 0), longAgo)),
                java.util.Optional.of(longAgo), false);

        PlayerAnalysisWindow merged = hydrator.merge(live, history);

        assertThat(merged.blocksMined()).isEqualTo(1100);
        assertThat(merged.distanceTravelled()).isEqualTo(250);
        assertThat(merged.discoveries()).hasSize(3);
        assertThat(merged.pathPoints()).hasSize(2);
        // The horizon is expressed by the window start, which is what a moderator sees as the span.
        assertThat(merged.windowStart()).isEqualTo(longAgo);
        assertThat(merged.windowEnd()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a discovery stored and still in memory is counted once")
    void mergeDeduplicatesTheSameDiscovery() {
        Instant time = NOW.minus(Duration.ofSeconds(30));
        OreDiscovery shared = hidden(7, time, 40);
        PlayerAnalysisWindow live = liveWindow(List.of(shared), 100, List.of());
        AnalysisHistory history = new AnalysisHistory(List.of(shared), 100, 0, List.of(),
                java.util.Optional.of(time), false);

        PlayerAnalysisWindow merged = hydrator.merge(live, history);

        // A session's discoveries are written to the database as well, so the same event legitimately
        // appears in both sources; counting it twice would double the evidence it carries.
        assertThat(merged.discoveries()).hasSize(1);
    }

    @Test
    @DisplayName("the seam is marked, and the interval spanning it is dropped rather than guessed")
    void mergeMarksTheSeamBetweenHistoryAndSession() {
        Instant longAgo = NOW.minus(Duration.ofDays(10));
        List<OreDiscovery> stored = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            stored.add(hidden(i, longAgo.plusSeconds(i * 600L), 50));
        }
        List<OreDiscovery> session = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            session.add(hidden(10 + i, NOW.minusSeconds(60 - i * 30L), 40));
        }
        PlayerAnalysisWindow live = liveWindow(session, 100, List.of());
        AnalysisHistory history = new AnalysisHistory(stored, 500, 0, List.of(),
                java.util.Optional.of(longAgo), false);

        PlayerAnalysisWindow merged = hydrator.merge(live, history);
        PlayerAnalysisWindow unseamed = PlayerAnalysisWindow.of(STEVE, OVERWORLD, longAgo, NOW,
                600, 0, merged.discoveries(), List.of());

        long observedMerged = countObserved(merged.hiddenDiscoveryIntervals());
        long observedUnseamed = countObserved(unseamed.hiddenDiscoveryIntervals());

        assertThat(merged.includesHistory()).isTrue();
        assertThat(merged.liveDiscoveryIndex()).isEqualTo(3);
        // Five hidden discoveries, but the one straddling a stretch of unknown length contributes no
        // interval: counting the session's effort alone would understate a legitimate waiting time and
        // push the verdict toward suspicion.
        assertThat(observedMerged).isEqualTo(4);
        assertThat(observedUnseamed).isEqualTo(5);
    }

    private static long countObserved(List<LikelihoodRatios.IntervalObservation> intervals) {
        return intervals.stream().filter(interval -> !interval.rightCensored()).count();
    }

    private static OreDiscoveryRepository.StoredDiscovery stored(String id, Instant time, int x) {
        return new OreDiscoveryRepository.StoredDiscovery(id, STEVE.id(), OVERWORLD.key(),
                hidden(x, time, 50));
    }

    private static PlayerAnalysisWindow liveWindow(List<OreDiscovery> discoveries, double blocksMined,
                                                   List<TrajectoryAnalysis.PathPoint> path) {
        return PlayerAnalysisWindow.of(STEVE, OVERWORLD, NOW.minus(Duration.ofMinutes(5)), NOW,
                blocksMined, 0, discoveries, path);
    }

    // ---------------------------------------------------------------------------------------
    // In-memory repositories
    // ---------------------------------------------------------------------------------------

    /** Emulates the real query, including its newest-first order and its limit. */
    private static final class FakeDiscoveries implements OreDiscoveryRepository {

        private final List<StoredDiscovery> stored = new ArrayList<>();
        private int findRecentCalls;

        @Override
        public void save(UUID playerId, String worldKey, String sessionId, OreDiscovery discovery) {
            stored.add(new StoredDiscovery("id-" + stored.size(), playerId, worldKey, discovery));
        }

        @Override
        public List<StoredDiscovery> findRecent(UUID playerId, Instant since, int limit) {
            findRecentCalls++;
            return stored.stream()
                    .filter(row -> row.playerId().equals(playerId))
                    .filter(row -> !row.discovery().time().isBefore(since))
                    .sorted(Comparator.comparing((StoredDiscovery row) -> row.discovery().time()).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StoredDiscovery> findByWorldAndOre(String worldKey, String oreId, Instant since,
                                                       int limit) {
            return stored.stream()
                    .filter(row -> row.worldKey().equals(worldKey))
                    .filter(row -> row.discovery().oreId().equals(oreId))
                    .filter(row -> !row.discovery().time().isBefore(since))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long deleteOlderThan(Instant cutoff) {
            long before = stored.size();
            stored.removeIf(row -> row.discovery().time().isBefore(cutoff));
            return before - stored.size();
        }
    }

    /** Emulates the real query, including its newest-first order. */
    private static final class FakeMining implements MiningEventRepository {

        private final List<Observation.Mining> events = new ArrayList<>();
        private int findRecentCalls;

        @Override
        public void saveAll(List<Observation.Mining> toSave) {
            events.addAll(toSave);
        }

        @Override
        public List<Observation.Mining> findRecent(UUID playerId, Instant since, int limit) {
            findRecentCalls++;
            return events.stream()
                    .filter(event -> event.player().id().equals(playerId))
                    .filter(event -> !event.timestamp().isBefore(since))
                    .sorted(Comparator.comparing(Observation.Mining::timestamp).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countSince(UUID playerId, String worldKey, Instant since) {
            return events.stream()
                    .filter(event -> event.player().id().equals(playerId))
                    .filter(event -> event.world().key().equals(worldKey))
                    .filter(event -> !event.timestamp().isBefore(since))
                    .count();
        }

        @Override
        public long deleteOlderThan(Instant cutoff) {
            long before = events.size();
            events.removeIf(event -> event.timestamp().isBefore(cutoff));
            return before - events.size();
        }
    }
}
