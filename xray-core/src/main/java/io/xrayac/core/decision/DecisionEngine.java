package io.xrayac.core.decision;

import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import java.time.Instant;

/**
 * Applies a {@link DecisionPolicy} to an evidence assessment.
 *
 * <p>Stateless and immutable; safe to share across workers.
 *
 * <h2>Order of evaluation</h2>
 * The engine first checks whether the evidence is worth acting on at all (the alert gate, which
 * combines the weakest evidence band with the alert confidence floor). Only then does it determine
 * the strongest action the evidence would justify, and finally it applies the enforcement mode,
 * which can only ever <i>downgrade</i> an action — {@code ALERT_ONLY} reduces a ban to an alert,
 * {@code BAN_WAVE} converts a ban into candidacy. A mode can never make enforcement easier than the
 * evidence allows, so a misconfigured mode cannot turn into a false ban.
 */
public final class DecisionEngine {

    private final DecisionPolicy policy;

    public DecisionEngine(DecisionPolicy policy) {
        this.policy = policy;
    }

    public DecisionPolicy policy() {
        return policy;
    }

    /**
     * Decides what to do about an assessed player.
     */
    public DecisionOutcome decide(SuspicionSnapshot snapshot, Instant now) {
        EvidenceStrength strength = snapshot.evidenceStrength();
        double confidence = snapshot.statisticalConfidence();

        if (strength.ordinal() < policy.minimumStrengthForAlert().ordinal()
                || confidence < policy.minimumConfidenceForAlert()) {
            return DecisionOutcome.none(snapshot, now, String.format(
                    "evidence band '%s' with confidence %.2f does not meet the alert threshold ('%s', %.2f)",
                    strength.label(), confidence,
                    policy.minimumStrengthForAlert().label(), policy.minimumConfidenceForAlert()));
        }

        ActionType justified = strongestJustifiedAction(strength, confidence);
        ActionType applied = applyEnforcementMode(justified);

        String reason = buildReason(justified, applied, snapshot);
        return new DecisionOutcome(applied, justified, reason, strength, confidence, now);
    }

    private ActionType strongestJustifiedAction(EvidenceStrength strength, double confidence) {
        if (strength.ordinal() >= policy.minimumStrengthForBan().ordinal()
                && confidence >= policy.minimumConfidenceForBan()) {
            return ActionType.BAN;
        }
        if (strength.ordinal() >= policy.minimumStrengthForKick().ordinal()
                && confidence >= policy.minimumConfidenceForBan()) {
            return ActionType.KICK;
        }
        if (strength.ordinal() >= policy.minimumStrengthForFlag().ordinal()) {
            return ActionType.FLAG;
        }
        return ActionType.ALERT;
    }

    private ActionType applyEnforcementMode(ActionType justified) {
        return switch (policy.mode()) {
            case IMMEDIATE -> justified;
            case ALERT_ONLY -> justified.isPlayerVisible() ? ActionType.ALERT : justified;
            case BAN_WAVE -> justified == ActionType.BAN ? ActionType.CANDIDATE : justified;
        };
    }

    private String buildReason(ActionType justified, ActionType applied, SuspicionSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("evidence '%s', confidence %.2f, %d observation(s) across %d independent signal(s)",
                snapshot.evidenceStrength().label(), snapshot.statisticalConfidence(),
                snapshot.sampleSize(), snapshot.independentGroups()));
        if (applied != justified) {
            sb.append(String.format("; the evidence justifies '%s' but the server is configured for '%s' mode, "
                    + "so the action taken is '%s'", justified, policy.mode(), applied));
        }
        return sb.toString();
    }
}
