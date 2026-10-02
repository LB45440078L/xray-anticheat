package io.xrayac.core.decision;

import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import java.time.Instant;

/**
 * The outcome of applying a policy to an assessment.
 *
 * <p>Carries both the action <b>taken</b> and the action the evidence <b>would have justified</b>
 * were the server in immediate-enforcement mode. That second field is not decoration: a server
 * running in alert-only mode needs to know whether the system merely noticed a player or would have
 * removed them, because those are very different things to see in a log and only the second is
 * evidence that the configuration is calibrated.
 *
 * @param action         what the system will actually do
 * @param justifiedAction what the evidence alone would justify, before the enforcement mode is applied
 * @param reason         a human-readable account of why
 * @param strength       the evidence band at the time of the decision
 * @param confidence     the statistical confidence at the time of the decision
 * @param decidedAt      when the decision was made
 */
public record DecisionOutcome(
        ActionType action,
        ActionType justifiedAction,
        String reason,
        EvidenceStrength strength,
        double confidence,
        Instant decidedAt) {

    public DecisionOutcome {
        if (action == null || justifiedAction == null || strength == null) {
            throw new IllegalArgumentException("action, justifiedAction and strength are required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("every decision must state a reason");
        }
        if (decidedAt == null) {
            throw new IllegalArgumentException("decidedAt is required");
        }
    }

    /** Whether the enforcement mode suppressed a stronger action than the one taken. */
    public boolean wasSuppressedByMode() {
        return action != justifiedAction;
    }

    public static DecisionOutcome none(SuspicionSnapshot snapshot, Instant at, String reason) {
        return new DecisionOutcome(ActionType.NONE, ActionType.NONE, reason,
                snapshot.evidenceStrength(), snapshot.statisticalConfidence(), at);
    }
}
