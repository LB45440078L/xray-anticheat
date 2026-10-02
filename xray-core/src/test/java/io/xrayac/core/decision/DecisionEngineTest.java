package io.xrayac.core.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the policy layer: that evidence maps onto the right action, that enforcement modes can
 * only ever soften a decision, and that ban waves respect interval, threshold and expiry rules.
 */
class DecisionEngineTest {

    private static final WorldId WORLD = WorldId.of("survival#minecraft:overworld");
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private static SuspicionSnapshot snapshot(EvidenceStrength strength, double confidence,
                                              int sampleSize, int groups) {
        return snapshot(PlayerRef.of(UUID.randomUUID(), "Subject"), strength, confidence, sampleSize, groups);
    }

    private static SuspicionSnapshot snapshot(PlayerRef player, EvidenceStrength strength, double confidence,
                                              int sampleSize, int groups) {
        double logOdds = switch (strength) {
            case INSUFFICIENT -> -3.0;
            case WEAK -> Math.log(4.0);
            case MODERATE -> Math.log(20.0);
            case STRONG -> Math.log(300.0);
            case VERY_STRONG -> Math.log(5000.0);
        };
        return new SuspicionSnapshot(
                player, WORLD, NOW,
                -3.9, logOdds, logOdds, 0.5, confidence, 1.0, sampleSize, groups, strength, List.of());
    }

    private static DecisionPolicy policy(EnforcementMode mode) {
        return new DecisionPolicy(mode, 0.5,
                EvidenceStrength.MODERATE, EvidenceStrength.STRONG, EvidenceStrength.STRONG,
                EvidenceStrength.VERY_STRONG, 0.75, DecisionPolicy.BanWavePolicy.defaults());
    }

    @Nested
    @DisplayName("mapping evidence to action")
    class MappingEvidence {

        @Test
        @DisplayName("weak evidence leads to no action at all")
        void weakEvidenceDoesNothing() {
            DecisionOutcome outcome = new DecisionEngine(policy(EnforcementMode.IMMEDIATE))
                    .decide(snapshot(EvidenceStrength.WEAK, 0.9, 50, 3), NOW);
            assertThat(outcome.action()).isEqualTo(ActionType.NONE);
        }

        @Test
        @DisplayName("strong evidence in immediate mode justifies a kick, very strong a ban")
        void escalatesWithStrength() {
            DecisionEngine engine = new DecisionEngine(policy(EnforcementMode.IMMEDIATE));

            assertThat(engine.decide(snapshot(EvidenceStrength.STRONG, 0.9, 50, 3), NOW).action())
                    .isEqualTo(ActionType.KICK);
            assertThat(engine.decide(snapshot(EvidenceStrength.VERY_STRONG, 0.9, 50, 3), NOW).action())
                    .isEqualTo(ActionType.BAN);
        }

        @Test
        @DisplayName("a ban is refused when confidence is below the irreversible-action floor")
        void lowConfidenceBlocksBan() {
            // Very strong evidence, but only 80% confidence: below the 0.75... no, above. Use 0.6.
            DecisionOutcome outcome = new DecisionEngine(policy(EnforcementMode.IMMEDIATE))
                    .decide(snapshot(EvidenceStrength.VERY_STRONG, 0.6, 50, 3), NOW);
            assertThat(outcome.action()).isNotEqualTo(ActionType.BAN);
        }
    }

    @Nested
    @DisplayName("enforcement modes only soften decisions")
    class EnforcementModes {

        @Test
        @DisplayName("alert-only mode never acts on the player, but records what the evidence justified")
        void alertOnlySuppressesEnforcement() {
            DecisionOutcome outcome = new DecisionEngine(policy(EnforcementMode.ALERT_ONLY))
                    .decide(snapshot(EvidenceStrength.VERY_STRONG, 0.95, 80, 4), NOW);

            assertThat(outcome.action()).isEqualTo(ActionType.ALERT);
            assertThat(outcome.justifiedAction()).isEqualTo(ActionType.BAN);
            assertThat(outcome.wasSuppressedByMode()).isTrue();
            assertThat(outcome.reason()).contains("ALERT_ONLY");
        }

        @Test
        @DisplayName("ban-wave mode converts a ban into a candidacy")
        void banWaveConvertsBanToCandidate() {
            DecisionOutcome outcome = new DecisionEngine(policy(EnforcementMode.BAN_WAVE))
                    .decide(snapshot(EvidenceStrength.VERY_STRONG, 0.95, 80, 4), NOW);

            assertThat(outcome.action()).isEqualTo(ActionType.CANDIDATE);
            assertThat(outcome.justifiedAction()).isEqualTo(ActionType.BAN);
        }

        @Test
        @DisplayName("a mode can never escalate beyond what the evidence supports")
        void modeCannotEscalate() {
            DecisionOutcome outcome = new DecisionEngine(policy(EnforcementMode.IMMEDIATE))
                    .decide(snapshot(EvidenceStrength.MODERATE, 0.8, 40, 3), NOW);
            assertThat(outcome.action()).isEqualTo(ActionType.ALERT);
        }
    }

    @Nested
    @DisplayName("ban waves")
    class BanWaves {

        private final BanWavePlanner planner = new BanWavePlanner();

        @Test
        @DisplayName("a candidate is recorded only when it meets the wave thresholds")
        void candidatesAreThresholdGated() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            List<BanWaveCandidate> none = planner.consider(List.of(),
                    snapshot(EvidenceStrength.MODERATE, 0.9, 50, 3), policy, NOW);
            assertThat(none).isEmpty();

            List<BanWaveCandidate> one = planner.consider(List.of(),
                    snapshot(EvidenceStrength.STRONG, 0.9, 50, 3), policy, NOW);
            assertThat(one).hasSize(1);
        }

        @Test
        @DisplayName("a wave does not run before the configured interval has elapsed")
        void intervalIsRespected() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            Instant lastWave = NOW.minus(Duration.ofMinutes(60));

            BanWavePlanner.PlanDecision decision = planner.plan(
                    twoStrongCandidates(), NOW, lastWave, policy);

            assertThat(decision.shouldRun()).isFalse();
            assertThat(decision.reason()).contains("interval");
        }

        @Test
        @DisplayName("a wave does not run with fewer candidates than configured")
        void minimumCandidatesEnforced() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            List<BanWaveCandidate> single = planner.consider(List.of(),
                    snapshot(EvidenceStrength.STRONG, 0.9, 50, 3), policy, NOW);

            BanWavePlanner.PlanDecision decision = planner.plan(single, NOW, null, policy);

            assertThat(decision.shouldRun()).isFalse();
            assertThat(decision.reason()).contains("at least");
        }

        @Test
        @DisplayName("a wave runs once the interval has elapsed and enough candidates exist")
        void waveRunsWhenEligible() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            Instant lastWave = NOW.minus(Duration.ofHours(48));

            BanWavePlanner.PlanDecision decision = planner.plan(
                    twoStrongCandidates(), NOW, lastWave, policy);

            assertThat(decision.shouldRun()).isTrue();
            assertThat(decision.planIfPresent().orElseThrow().size()).isEqualTo(2);
            assertThat(decision.planIfPresent().orElseThrow().automaticBan()).isFalse();
        }

        @Test
        @DisplayName("stale candidates expire and are not banned on old evidence")
        void expiryRemovesStaleCandidates() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            List<BanWaveCandidate> candidates = twoStrongCandidates();

            // Far beyond the default 336-hour TTL.
            Instant muchLater = NOW.plus(Duration.ofDays(60));
            assertThat(planner.pruneExpired(candidates, muchLater, policy)).isEmpty();

            assertThat(planner.plan(candidates, muchLater, null, policy).shouldRun()).isFalse();
        }

        @Test
        @DisplayName("a disabled ban wave never runs and never records candidates")
        void disabledBanWave() {
            DecisionPolicy policy = new DecisionPolicy(EnforcementMode.BAN_WAVE, 0.5,
                    EvidenceStrength.MODERATE, EvidenceStrength.STRONG, EvidenceStrength.STRONG,
                    EvidenceStrength.VERY_STRONG, 0.75,
                    new DecisionPolicy.BanWavePolicy(false, 1440, 336,
                            EvidenceStrength.STRONG, 0.85, 2, 2, false));

            assertThat(planner.consider(List.of(), snapshot(EvidenceStrength.VERY_STRONG, 0.99, 90, 5),
                    policy, NOW)).isEmpty();
            assertThat(planner.plan(twoStrongCandidates(), NOW, null, policy).reason())
                    .contains("disabled");
        }

        @Test
        @DisplayName("merging a stronger assessment preserves the original detection time")
        void mergePreservesFirstDetection() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            // A single fixed player, so the two assessments genuinely refer to the same candidate.
            PlayerRef player = PlayerRef.of(UUID.randomUUID(), "RepeatOffender");

            List<BanWaveCandidate> first = planner.consider(List.of(),
                    snapshot(player, EvidenceStrength.STRONG, 0.9, 50, 3), policy, NOW);
            Instant later = NOW.plus(Duration.ofHours(5));
            List<BanWaveCandidate> second = planner.consider(first,
                    snapshot(player, EvidenceStrength.VERY_STRONG, 0.95, 80, 4), policy, later);

            assertThat(second).hasSize(1);
            assertThat(second.getFirst().firstDetected()).isEqualTo(NOW);
            assertThat(second.getFirst().lastDetected()).isEqualTo(later);
            assertThat(second.getFirst().peakStrength()).isEqualTo(EvidenceStrength.VERY_STRONG);
        }

        private List<BanWaveCandidate> twoStrongCandidates() {
            DecisionPolicy policy = policy(EnforcementMode.BAN_WAVE);
            List<BanWaveCandidate> list = planner.consider(List.of(),
                    snapshot(EvidenceStrength.STRONG, 0.9, 50, 3), policy, NOW.minusSeconds(7200));
            return planner.consider(list,
                    snapshot(EvidenceStrength.STRONG, 0.9, 50, 3), policy, NOW.minusSeconds(7000));
        }
    }

    @Nested
    @DisplayName("policy validation")
    class PolicyValidation {

        @Test
        @DisplayName("a ban cannot be easier to trigger than an alert")
        void banCannotBeWeakerThanAlert() {
            assertThatThrownBy(() -> new DecisionPolicy(EnforcementMode.IMMEDIATE, 0.5,
                    EvidenceStrength.STRONG, EvidenceStrength.STRONG, EvidenceStrength.STRONG,
                    EvidenceStrength.WEAK, 0.9, DecisionPolicy.BanWavePolicy.defaults()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the ban confidence floor cannot be below the alert floor")
        void confidenceFloorsOrdered() {
            assertThatThrownBy(() -> new DecisionPolicy(EnforcementMode.IMMEDIATE, 0.8,
                    EvidenceStrength.MODERATE, EvidenceStrength.STRONG, EvidenceStrength.STRONG,
                    EvidenceStrength.VERY_STRONG, 0.4, DecisionPolicy.BanWavePolicy.defaults()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
