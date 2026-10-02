package io.xrayac.core.decision;

import io.xrayac.core.evidence.EvidenceStrength;

/**
 * The administrative policy that maps an evidence assessment onto an action.
 *
 * <h2>Why this is separate from the statistics</h2>
 * The statistical engine answers "how strongly does this behaviour favour the ore-vision
 * hypothesis?". It deliberately does not answer "and therefore what?". Those are different
 * questions with different owners: the first is a property of the data, the second is a policy
 * choice that a server's staff must make and be accountable for. Keeping them apart means an
 * administrator can make the system stricter or more lenient — or switch it to alert-only — without
 * invalidating a single line of mathematics or a single unit test.
 *
 * <h2>The two independent gates</h2>
 * Every enforcement decision passes through <b>two</b> gates, and both must open:
 * <ol>
 *   <li><b>Evidence strength</b> — which band the accumulated evidence reached.</li>
 *   <li><b>Statistical confidence</b> — how much the assessment can be trusted given sample size and
 *       independence. A very strong ratio computed from four observations must not be able to ban
 *       anyone, so confidence carries its own floor.</li>
 * </ol>
 * The strongest, irreversible actions additionally require a higher confidence floor than the
 * weaker, reversible ones. Getting a ban wrong is not the same size of mistake as sending an alert.
 *
 * @param mode                       how enforcement is executed
 * @param minimumConfidenceForAlert  confidence floor below which nothing is even reported
 * @param minimumStrengthForAlert    evidence band required before a moderator is told
 * @param minimumStrengthForFlag     band required to record a formal flag
 * @param minimumStrengthForKick     band required to disconnect a player
 * @param minimumStrengthForBan      band required to remove a player
 * @param minimumConfidenceForBan    confidence floor for irreversible action, normally above the alert floor
 * @param banWave                    parameters of batched enforcement
 */
public record DecisionPolicy(
        EnforcementMode mode,
        double minimumConfidenceForAlert,
        EvidenceStrength minimumStrengthForAlert,
        EvidenceStrength minimumStrengthForFlag,
        EvidenceStrength minimumStrengthForKick,
        EvidenceStrength minimumStrengthForBan,
        double minimumConfidenceForBan,
        BanWavePolicy banWave) {

    public DecisionPolicy {
        if (mode == null || banWave == null) {
            throw new IllegalArgumentException("mode and banWave policy are required");
        }
        requireUnitInterval(minimumConfidenceForAlert, "minimumConfidenceForAlert");
        requireUnitInterval(minimumConfidenceForBan, "minimumConfidenceForBan");
        if (minimumConfidenceForBan < minimumConfidenceForAlert) {
            // Irreversible action must never be easier to trigger than a mere notification.
            throw new IllegalArgumentException(
                    "minimumConfidenceForBan must be at least minimumConfidenceForAlert");
        }
        if (minimumStrengthForBan.ordinal() < minimumStrengthForAlert.ordinal()) {
            throw new IllegalArgumentException(
                    "the evidence required to ban cannot be weaker than the evidence required to alert");
        }
    }

    private static void requireUnitInterval(double value, String name) {
        if (!(value >= 0.0) || !(value <= 1.0)) {
            throw new IllegalArgumentException(name + " must lie in [0, 1], got " + value);
        }
    }

    /**
     * Conservative defaults: report freely, enforce only on strong evidence.
     *
     * <p>The default mode is {@link EnforcementMode#ALERT_ONLY}. Automated punishment is opt-in, and
     * a fresh installation should gather evidence and let humans judge it before it is trusted to
     * act. A server that wants enforcement turns it on deliberately.
     */
    public static DecisionPolicy defaults() {
        return new DecisionPolicy(
                EnforcementMode.ALERT_ONLY,
                0.5,
                EvidenceStrength.MODERATE,
                EvidenceStrength.STRONG,
                EvidenceStrength.STRONG,
                EvidenceStrength.VERY_STRONG,
                0.75,
                BanWavePolicy.defaults());
    }

    /**
     * Parameters of deferred, batched enforcement.
     *
     * <h2>Why ban waves exist</h2>
     * Banning the instant a threshold is crossed has three problems. It removes the cheater while
     * their technique is still working, so they tell their friends exactly what tripped it and the
     * community learns the threshold; it acts on a single moment rather than a body of evidence; and
     * it produces a trickle of bans that is easy to attribute to a specific behaviour. Batching
     * lets the system accumulate evidence, compare players, and enforce once enough independent
     * cases exist — and it lets the evidence be recomputed from stored data immediately before
     * anyone is removed.
     *
     * @param enabled                whether batched enforcement is used at all
     * @param intervalMinutes        minimum time between waves
     * @param candidateTtlHours      how long a candidate remains eligible before its evidence is
     *                               considered stale
     * @param minimumStrength        evidence band a candidate must have reached
     * @param minimumConfidence      confidence a candidate must have reached
     * @param minimumIndependentSignals independent signal families required per candidate
     * @param minimumCandidates      how many eligible candidates must exist before a wave is worth
     *                               running; a wave of one is just a delayed immediate ban, which
     *                               defeats the purpose
     * @param automaticBan           whether a wave bans automatically or only proposes a list for a
     *                               moderator to approve
     */
    public record BanWavePolicy(
            boolean enabled,
            long intervalMinutes,
            long candidateTtlHours,
            EvidenceStrength minimumStrength,
            double minimumConfidence,
            int minimumIndependentSignals,
            int minimumCandidates,
            boolean automaticBan) {

        public BanWavePolicy {
            if (intervalMinutes <= 0) {
                throw new IllegalArgumentException("intervalMinutes must be > 0");
            }
            if (candidateTtlHours <= 0) {
                throw new IllegalArgumentException("candidateTtlHours must be > 0");
            }
            requireUnitInterval(minimumConfidence, "minimumConfidence");
            if (minimumIndependentSignals < 1) {
                throw new IllegalArgumentException("minimumIndependentSignals must be at least 1");
            }
            if (minimumCandidates < 1) {
                throw new IllegalArgumentException("minimumCandidates must be at least 1");
            }
        }

        public static BanWavePolicy defaults() {
            return new BanWavePolicy(true, 1440, 336, EvidenceStrength.STRONG, 0.85, 2, 2, false);
        }
    }
}
