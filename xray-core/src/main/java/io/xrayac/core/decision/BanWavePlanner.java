package io.xrayac.core.decision;

import io.xrayac.core.evidence.SuspicionSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maintains the candidate list and decides when a ban wave should run.
 *
 * <p>Stateless with respect to the candidates themselves — they are passed in and a new list comes
 * out — so the caller owns persistence and this class stays a pure function of its inputs, which is
 * what makes it testable and replayable.
 */
public final class BanWavePlanner {

    /**
     * Adds or updates a candidate if the assessment meets the wave thresholds.
     *
     * @return the existing list, possibly with the player added or strengthened
     */
    public List<BanWaveCandidate> consider(List<BanWaveCandidate> existing,
                                           SuspicionSnapshot snapshot,
                                           DecisionPolicy policy,
                                           Instant now) {

        DecisionPolicy.BanWavePolicy wave = policy.banWave();
        if (!wave.enabled() || policy.mode() != EnforcementMode.BAN_WAVE) {
            return List.copyOf(existing);
        }
        if (!meetsThreshold(snapshot, wave)) {
            return List.copyOf(existing);
        }

        Map<java.util.UUID, BanWaveCandidate> byPlayer = new LinkedHashMap<>();
        for (BanWaveCandidate candidate : existing) {
            byPlayer.put(candidate.player().id(), candidate);
        }

        java.util.UUID id = snapshot.player().id();
        BanWaveCandidate updated = byPlayer.containsKey(id)
                ? byPlayer.get(id).mergedWith(snapshot, now)
                : BanWaveCandidate.from(snapshot.player(), snapshot.world(), snapshot, now);
        byPlayer.put(id, updated);

        return List.copyOf(byPlayer.values());
    }

    /**
     * Removes candidates whose evidence has expired.
     *
     * <p>Expiry is not mercy: a player who was flagged once, eight months ago, and has shown nothing
     * since should not be banned on the strength of a stale signal, because behaviour changes and
     * a single old assessment is exactly the kind of thin evidence this system exists to avoid.
     */
    public List<BanWaveCandidate> pruneExpired(List<BanWaveCandidate> candidates,
                                               Instant now,
                                               DecisionPolicy policy) {
        long ttlMillis = Duration.ofHours(policy.banWave().candidateTtlHours()).toMillis();
        List<BanWaveCandidate> kept = new ArrayList<>();
        for (BanWaveCandidate candidate : candidates) {
            if (candidate.ageMillis(now) <= ttlMillis) {
                kept.add(candidate);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Decides whether a wave should run now, and over which candidates.
     *
     * <p>A wave requires all of: the mode to be {@link EnforcementMode#BAN_WAVE}, batched enforcement
     * to be enabled, the inter-wave interval to have elapsed since {@code lastWaveAt}, and at least
     * {@code minimumCandidates} eligible candidates. Returning a deliberate list for moderator
     * approval (rather than banning) is controlled by {@code automaticBan}.
     *
     * @param lastWaveAt  when the previous wave ran, or {@code null} if none has ever run
     */
    public PlanDecision plan(List<BanWaveCandidate> candidates,
                             Instant now,
                             Instant lastWaveAt,
                             DecisionPolicy policy) {

        if (policy.mode() != EnforcementMode.BAN_WAVE) {
            return PlanDecision.notNow("the server is not in ban-wave mode");
        }
        DecisionPolicy.BanWavePolicy wave = policy.banWave();
        if (!wave.enabled()) {
            return PlanDecision.notNow("batched enforcement is disabled in configuration");
        }
        if (lastWaveAt != null) {
            long since = Duration.between(lastWaveAt, now).toMinutes();
            if (since < wave.intervalMinutes()) {
                return PlanDecision.notNow(String.format(
                        "only %d minute(s) since the last wave; the configured interval is %d",
                        since, wave.intervalMinutes()));
            }
        }

        List<BanWaveCandidate> eligible = pruneExpired(candidates, now, policy).stream()
                .filter(c -> c.peakStrength().ordinal() >= wave.minimumStrength().ordinal())
                .filter(c -> c.peakConfidence() >= wave.minimumConfidence())
                .filter(c -> c.independentGroups() >= wave.minimumIndependentSignals())
                .toList();

        if (eligible.size() < wave.minimumCandidates()) {
            return PlanDecision.notNow(String.format(
                    "%d eligible candidate(s); at least %d are required before a wave is worth running",
                    eligible.size(), wave.minimumCandidates()));
        }

        return new PlanDecision(new BanWavePlan(now, eligible, wave.automaticBan()), null);
    }

    private boolean meetsThreshold(SuspicionSnapshot snapshot, DecisionPolicy.BanWavePolicy policy) {
        return snapshot.evidenceStrength().ordinal() >= policy.minimumStrength().ordinal()
                && snapshot.statisticalConfidence() >= policy.minimumConfidence()
                && snapshot.independentGroups() >= policy.minimumIndependentSignals();
    }

    /**
     * The result of asking whether a wave should run.
     *
     * @param plan   the plan, when a wave should run now
     * @param reason why not, when it should not
     */
    public record PlanDecision(BanWavePlan plan, String reason) {

        public PlanDecision {
            if ((plan == null) == (reason == null)) {
                throw new IllegalArgumentException("exactly one of plan and reason must be present");
            }
        }

        public static PlanDecision notNow(String reason) {
            return new PlanDecision(null, reason);
        }

        public boolean shouldRun() {
            return plan != null;
        }

        public Optional<BanWavePlan> planIfPresent() {
            return Optional.ofNullable(plan);
        }
    }
}
