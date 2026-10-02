package io.xrayac.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.config.MapOreProfileRegistry;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.component.ExposureMixComponent;
import io.xrayac.core.evidence.component.HiddenDiscoveryRateComponent;
import io.xrayac.core.evidence.component.InterDiscoveryWaitingComponent;
import io.xrayac.core.evidence.component.OreTargetingComponent;
import io.xrayac.core.evidence.component.TunnelGeometryComponent;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end validation of the evidence engine against the canonical synthetic scenarios.
 *
 * <p>These tests are the specification of the system's judgement. Each one encodes a claim about
 * how a particular kind of player must be treated, and the assertions are written in terms of the
 * decisions a moderator would actually take on — the verdict band, the independence of the signals,
 * and the presence of recorded exculpatory evidence — rather than in terms of intermediate numbers.
 * If a future change makes the engine flag cave explorers, or lets three lucky finds reach a
 * decision, these tests fail.
 */
class EvidenceEngineTest {

    private static final WorldId WORLD = WorldId.of("survival#minecraft:overworld");
    private static final PlayerRef PLAYER = PlayerRef.of(UUID.randomUUID(), "Tester");
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final OreProfileRegistry PROFILES =
            MapOreProfileRegistry.of(MapOreProfileRegistry.defaults());
    private static final EvidenceParameters PARAMS = EvidenceParameters.defaults();

    private static final EvidenceEngine ENGINE = new EvidenceEngine(List.of(
            new HiddenDiscoveryRateComponent(),
            new InterDiscoveryWaitingComponent(),
            new OreTargetingComponent(),
            new ExposureMixComponent(),
            new TunnelGeometryComponent()));

    // ---------------------------------------------------------------------------------------
    // Scenario construction helpers
    // ---------------------------------------------------------------------------------------

    private static OreDiscovery discovery(String oreId, int x, ExposureState state,
                                          double blocksSincePrevious,
                                          double moveAngle, double lookAngle) {
        TrajectoryAnalysis.Approach approach = (moveAngle < 0)
                ? TrajectoryAnalysis.Approach.noData()
                : new TrajectoryAnalysis.Approach(true, 8.0, moveAngle, lookAngle, 1.0);
        return new OreDiscovery(oreId, BlockPos.of(x, -59, 0), NOW.minusSeconds(600),
                state, 4, state == ExposureState.HIDDEN ? 4 : 0, state == ExposureState.HIDDEN ? 0 : 4,
                blocksSincePrevious, blocksSincePrevious, approach, 1.0);
    }

    /** A straight tunnel: points march along +X, giving high straightness and efficiency. */
    private static List<TrajectoryAnalysis.PathPoint> straightPath() {
        List<TrajectoryAnalysis.PathPoint> path = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            path.add(TrajectoryAnalysis.PathPoint.of(new Vector3(i, -59, 0), NOW.minusSeconds(600 - i)));
        }
        return path;
    }

    /** A wandering cave path: direction changes constantly, low straightness and efficiency. */
    private static List<TrajectoryAnalysis.PathPoint> meanderingPath() {
        List<TrajectoryAnalysis.PathPoint> path = new ArrayList<>();
        double x = 0;
        double z = 0;
        for (int i = 0; i < 60; i++) {
            double angle = i * 2.3;
            x += Math.cos(angle) * 3;
            z += Math.sin(angle) * 3;
            path.add(TrajectoryAnalysis.PathPoint.of(new Vector3(x, -30 + (i % 5), z),
                    NOW.minusSeconds(600 - i)));
        }
        return path;
    }

    /** A steady strip-mine path along one axis. */
    private static List<TrajectoryAnalysis.PathPoint> stripMinePath() {
        List<TrajectoryAnalysis.PathPoint> path = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            path.add(TrajectoryAnalysis.PathPoint.of(new Vector3(i * 2, -59, 0), NOW.minusSeconds(600 - i)));
        }
        return path;
    }

    private static PlayerAnalysisWindow window(double blocksMined, double distance,
                                               List<OreDiscovery> discoveries,
                                               List<TrajectoryAnalysis.PathPoint> path) {
        return PlayerAnalysisWindow.of(PLAYER, WORLD, NOW.minusSeconds(3600), NOW,
                blocksMined, distance, discoveries, path);
    }

    @Nested
    @DisplayName("legitimate cave exploration")
    class LegitimateCaveExplorer {

        @Test
        @DisplayName("a player finding only exposed ore in caves is not flagged, and the evidence says why")
        void caveExplorerIsNotSuspicious() {
            // Thirty discoveries, all of them ore lying open in a cave, along a wandering path.
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                discoveries.add(discovery("diamond", i * 20,
                        i % 3 == 0 ? ExposureState.FULLY_EXPOSED : ExposureState.PARTIALLY_EXPOSED,
                        75, -1, -1));
            }
            PlayerAnalysisWindow cave = window(1500, 900, discoveries, meanderingPath());

            SuspicionSnapshot snapshot = ENGINE.evaluate(cave, PROFILES, PARAMS, NOW);

            assertThat(cave.hiddenCount()).isZero();
            assertThat(snapshot.evidenceStrength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
            assertThat(snapshot.suspicionScore()).isLessThanOrEqualTo(0.02 + 1e-9);
            // The engine must record an explicit argument in the player's favour, not merely fail
            // to find one against them.
            assertThat(snapshot.exculpatoryContributions()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("suspicious ore targeting")
    class SuspiciousOreTargeter {

        @Test
        @DisplayName("an ore-vision user is flagged, on multiple independent signals")
        void targeterIsFlagged() {
            // Forty buried diamonds over only 3000 blocks mined (an ore-vision user wastes very
            // little rock), every approach lined up with the ore, along a perfectly straight path.
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                discoveries.add(discovery("diamond", i * 10, ExposureState.HIDDEN, 75, 4.0, 3.0));
            }
            PlayerAnalysisWindow targeter = window(3000, 2000, discoveries, straightPath());

            SuspicionSnapshot snapshot = ENGINE.evaluate(targeter, PROFILES, PARAMS, NOW);

            assertThat(snapshot.evidenceStrength())
                    .isIn(EvidenceStrength.MODERATE, EvidenceStrength.STRONG, EvidenceStrength.VERY_STRONG);
            assertThat(snapshot.suspicionScore()).isGreaterThan(0.5);
            // The anti-evasion property: a verdict must rest on more than one independent family.
            assertThat(snapshot.independentGroups()).isGreaterThanOrEqualTo(PARAMS.minimumIndependentGroups());
            // The rate signal and the targeting signal must both actually have spoken.
            assertThat(snapshot.contributionsByImpact())
                    .extracting(EvidenceContribution::independentGroup)
                    .contains("discovery-rate", "targeting");
        }
    }

    @Nested
    @DisplayName("sample-size restraint")
    class SampleSizeRestraint {

        @Test
        @DisplayName("a very lucky player with a handful of finds cannot reach a verdict")
        void luckyPlayerIsNotConvictable() {
            // Six buried diamonds over just 500 blocks mined. The rate is extraordinary, but six
            // observations are nowhere near enough to conclude anything.
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                discoveries.add(discovery("diamond", i * 10, ExposureState.HIDDEN, 80, 2.0, 2.0));
            }
            PlayerAnalysisWindow lucky = window(500, 400, discoveries, straightPath());

            SuspicionSnapshot snapshot = ENGINE.evaluate(lucky, PROFILES, PARAMS, NOW);

            assertThat(snapshot.sampleSize()).isLessThan(PARAMS.minimumSampleSize());
            assertThat(snapshot.evidenceStrength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
        }

        @Test
        @DisplayName("a single strong signal family is not enough on its own")
        void singleSignalIsNotASignal() {
            // Only the exposure-mix component can speak here: no waiting times, no targeting data.
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                discoveries.add(discovery("diamond", i * 10, ExposureState.HIDDEN, 75, -1, -1));
            }
            PlayerAnalysisWindow noApproach = window(3000, 2000, discoveries, meanderingPath());

            SuspicionSnapshot snapshot = ENGINE.evaluate(noApproach, PROFILES, PARAMS, NOW);
            long distinctGroups = snapshot.contributionsByImpact().stream()
                    .map(EvidenceContribution::independentGroup)
                    .distinct()
                    .count();

            // Whatever the score, the verdict cannot be actionable if fewer than the configured
            // number of independent families contributed.
            if (distinctGroups < PARAMS.minimumIndependentGroups()) {
                assertThat(snapshot.evidenceStrength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
            }
        }
    }

    @Nested
    @DisplayName("legitimate strip mining")
    class LegitimateStripMiner {

        @Test
        @DisplayName("a straight tunnel with a normal yield is not flagged by geometry")
        void stripMinerIsNotSuspicious() {
            // Eight buried diamonds over 5000 blocks of straight strip mining: right at the
            // legitimate expectation. The straight path must not itself add suspicion.
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                discoveries.add(discovery("diamond", i * 20, ExposureState.HIDDEN, 625, -1, -1));
            }
            PlayerAnalysisWindow stripMiner = window(5000, 4000, discoveries, stripMinePath());

            SuspicionSnapshot snapshot = ENGINE.evaluate(stripMiner, PROFILES, PARAMS, NOW);

            assertThat(snapshot.suspicionScore()).isLessThan(0.5);
            // Geometry must not be able to reach an actionable verdict on its own.
            assertThat(snapshot.evidenceStrength()).isNotEqualTo(EvidenceStrength.VERY_STRONG);
            assertThat(snapshot.evidenceStrength()).isNotEqualTo(EvidenceStrength.STRONG);
        }
    }

    @Nested
    @DisplayName("multiplayer contamination")
    class MultiplayerContamination {

        @Test
        @DisplayName("ore taken from another player's tunnel does not count as a hidden discovery")
        void tunnelFollowerIsNotSuspicious() {
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                // ExposureState.CONDITIONALLY_EXPOSED is what the analyser produces for ore in
                // somebody else's tunnel; such discoveries are not "hidden".
                discoveries.add(new OreDiscovery("diamond", BlockPos.of(i * 10, -59, 0),
                        NOW.minusSeconds(600), ExposureState.CONDITIONALLY_EXPOSED,
                        4, 0, 4, 200, 200, TrajectoryAnalysis.Approach.noData(), 1.0));
            }
            PlayerAnalysisWindow follower = window(4000, 3000, discoveries, stripMinePath());

            SuspicionSnapshot snapshot = ENGINE.evaluate(follower, PROFILES, PARAMS, NOW);

            assertThat(snapshot.suspicionScore()).isLessThan(0.5);
            assertThat(snapshot.evidenceStrength()).isNotIn(EvidenceStrength.STRONG, EvidenceStrength.VERY_STRONG);
        }
    }

    @Nested
    @DisplayName("evidence decay")
    class EvidenceDecay {

        @Test
        @DisplayName("the same window evaluated long after the fact carries proportionally less weight")
        void decayReducesEvidence() {
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                discoveries.add(discovery("diamond", i * 10, ExposureState.HIDDEN, 75, 4.0, 3.0));
            }
            PlayerAnalysisWindow targeter = window(3000, 2000, discoveries, straightPath());

            SuspicionSnapshot fresh = ENGINE.evaluate(targeter, PROFILES, PARAMS, NOW);
            // Six months of half-life intervals later (26 weeks -> ~7 half lives).
            SuspicionSnapshot stale = ENGINE.evaluate(targeter, PROFILES, PARAMS,
                    NOW.plusSeconds(26L * 7 * 24 * 3600));

            assertThat(stale.decayFactor()).isLessThan(fresh.decayFactor());
            assertThat(stale.effectiveLogOdds()).isLessThan(fresh.effectiveLogOdds());
        }
    }

    @Nested
    @DisplayName("explainability")
    class Explainability {

        @Test
        @DisplayName("every contribution carries a readable explanation and the snapshot renders them")
        void everythingIsExplainable() {
            List<OreDiscovery> discoveries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                discoveries.add(discovery("diamond", i * 10, ExposureState.HIDDEN, 75, 4.0, 3.0));
            }
            PlayerAnalysisWindow targeter = window(3000, 2000, discoveries, straightPath());
            SuspicionSnapshot snapshot = ENGINE.evaluate(targeter, PROFILES, PARAMS, NOW);

            assertThat(snapshot.contributions())
                    .allSatisfy(c -> assertThat(c.explanation()).isNotBlank());

            String report = snapshot.explain();
            assertThat(report).contains("Suspicion assessment");
            assertThat(report).contains("statistical confid.");
            assertThat(report).contains("independent signal");
        }
    }

    @Nested
    @DisplayName("uncertainty must not become leniency")
    class UncertaintyIsNotLeniency {

        @Test
        @DisplayName("UNKNOWN discoveries are excluded from the exposure mix, never counted as exposed")
        void unknownIsExcludedFromTheMix() {
            // Control: ten discoveries that were plainly visible in a cave.
            List<OreDiscovery> control = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                control.add(discovery("diamond", i * 20, ExposureState.FULLY_EXPOSED, 100, -1, -1));
            }
            SuspicionSnapshot controlSnapshot = ENGINE.evaluate(
                    window(1000, 800, control, meanderingPath()), PROFILES, PARAMS, NOW);

            // The same ten, plus ten more whose surroundings could not be read at all.
            List<OreDiscovery> withUnknown = new ArrayList<>(control);
            for (int i = 0; i < 10; i++) {
                withUnknown.add(discovery("diamond", 1000 + i * 20, ExposureState.UNKNOWN, 100, -1, -1));
            }
            SuspicionSnapshot unknownSnapshot = ENGINE.evaluate(
                    window(2000, 1600, withUnknown, meanderingPath()), PROFILES, PARAMS, NOW);

            // The exposure-mix signal must be byte-for-byte equivalent: unreadable ore neither helps
            // the player nor hurts them. Crediting it as "found in the open" would hand out
            // exculpatory evidence for missing information.
            assertThat(contributionOf(unknownSnapshot, "exposure-mix").logLikelihoodRatio())
                    .isCloseTo(contributionOf(controlSnapshot, "exposure-mix").logLikelihoodRatio(),
                            org.assertj.core.data.Offset.offset(1e-9));
            assertThat(contributionOf(unknownSnapshot, "exposure-mix").sampleSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("ore in another player's tunnel is explained away, not credited as cave visibility")
        void otherPlayersTunnelIsNotExculpatory() {
            List<OreDiscovery> tunnel = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tunnel.add(discovery("diamond", i * 20, ExposureState.CONDITIONALLY_EXPOSED, 100, -1, -1));
            }
            SuspicionSnapshot snapshot = ENGINE.evaluate(
                    window(1000, 800, tunnel, meanderingPath()), PROFILES, PARAMS, NOW);

            // Nothing here is classifiable, so the mix must be silent rather than recording ten
            // discoveries as though the player had found them lying open in a cave.
            assertThat(contributionOf(snapshot, "exposure-mix").sampleSize()).isZero();
        }

        private EvidenceContribution contributionOf(SuspicionSnapshot snapshot, String componentId) {
            return snapshot.contributions().stream()
                    .filter(c -> c.componentId().equals(componentId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("component " + componentId + " produced no result"));
        }
    }
}
