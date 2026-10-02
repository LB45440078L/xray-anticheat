package io.xrayac.core.decision;

/**
 * What the system proposes to do about a player.
 *
 * <p>The action is chosen by a {@link DecisionPolicy} from an evidence assessment; the assessment
 * never selects an action itself. That separation is what allows an administrator to run the same
 * mathematics in "alert only" mode for a month before trusting it with enforcement, and to change
 * the consequence of a given strength of evidence without touching the model.
 */
public enum ActionType {

    /** Do nothing. The evidence does not warrant intervention. */
    NONE,

    /** Make the evidence available to moderators without taking any action against the player. */
    ALERT,

    /** Record the assessment as a formal flag on the player's record, still without enforcement. */
    FLAG,

    /** Persist the player as a ban-wave candidate and keep collecting evidence. */
    CANDIDATE,

    /** Disconnect the player without a ban. Reversible and low-consequence. */
    KICK,

    /** Permanently remove the player. The most severe action the system can propose. */
    BAN;

    /** Whether this action changes the player's experience of the server. */
    public boolean isPlayerVisible() {
        return this == KICK || this == BAN;
    }

    /** Whether this action is irreversible for the player. */
    public boolean isIrreversible() {
        return this == BAN;
    }
}
