package io.xrayac.core.domain;

/**
 * How visible an ore block was to a player <b>before</b> that player mined it.
 *
 * <p>This is deliberately not a boolean. The distinction between "the player could see this
 * ore through a natural cave opening" and "the player could not have seen this ore without
 * cheating" is the single most important classification in the whole system, and collapsing
 * it to true/false would silently discard exactly the uncertainty the model needs to be
 * honest about.
 */
public enum ExposureState {

    /**
     * The ore had one or more faces open to a natural or pre-existing cavity at the moment it
     * was observed. A player standing in that cavity could have seen it by ordinary play.
     */
    FULLY_EXPOSED,

    /**
     * The ore was not open to a cavity, but was adjacent (within the configured visibility
     * radius, including diagonals through transparent blocks) to visible space, so it could
     * plausibly have been seen or reasonably inferred from what was visible.
     */
    PARTIALLY_EXPOSED,

    /**
     * The ore was enclosed on all six orthogonal faces at observation time, <i>but</i> the
     * enclosure is itself the result of the same player's recent excavation, or is otherwise
     * attributable to a modification we can date. In other words: it is hidden now, but it was
     * very likely exposed by the time the player reached it.
     *
     * <p>This state exists so that "hidden now" does not automatically mean "hidden then" — the
     * commonest source of false positives in naive X-ray detectors.
     */
    CONDITIONALLY_EXPOSED,

    /**
     * The ore was enclosed and we have no plausible account of how the player could have
     * perceived it. This is the state that carries evidentiary weight for the X-ray
     * hypothesis.
     */
    HIDDEN,

    /**
     * The surrounding world state could not be established with confidence — for example the
     * neighbouring chunk was not loaded, the block history has been pruned, or another plugin
     * modified the terrain in a way we cannot date. Evidence derived from an UNKNOWN state is
     * discounted rather than assumed.
     */
    UNKNOWN;

    /** True when the ore could plausibly have been seen by ordinary means. */
    public boolean isVisibleByOrdinaryPlay() {
        return this == FULLY_EXPOSED || this == PARTIALLY_EXPOSED;
    }

    /**
     * The most visible classification among a set, used to judge a whole ore vein.
     *
     * <p>A vein must be judged by its most visible member, not by whichever block a player happened to
     * break first. Mining into a partly-open vein often means the first block struck is an enclosed
     * one — approaching from the side, or breaking inward from an adjacent tunnel — and judging the
     * vein on that block alone would record a discovery the player could plainly see as "hidden".
     * Since a player who can see one block of a vein can reasonably mine all of it, the honest verdict
     * for the vein is the most visible state any of its blocks reached.
     *
     * <p>Ordering reuses the {@link #hiddennessWeight()} table, so the "most visible" state is simply
     * the one with the smallest weight. That places {@code UNKNOWN} above {@code HIDDEN}, which is
     * deliberate: uncertainty about a vein must never be resolved into the state that carries
     * evidentiary weight against the player.
     *
     * @return the most visible state, or {@link #UNKNOWN} when the collection is empty
     */
    public static ExposureState mostVisible(java.util.Collection<ExposureState> states) {
        ExposureState best = null;
        for (ExposureState state : states) {
            if (state == null) {
                continue;
            }
            if (best == null || state.hiddennessWeight() < best.hiddennessWeight()) {
                best = state;
            }
        }
        return best == null ? UNKNOWN : best;
    }

    /** True when this state is the one that supports the ore-informed hypothesis. */
    public boolean isUnaccountablyHidden() {
        return this == HIDDEN;
    }

    /**
     * A rough ordinal "hiddenness" weight in {@code [0, 1]} used to scale evidence.
     *
     * <p>UNKNOWN sits deliberately in the middle rather than at an extreme: we neither
     * penalise the player for missing information nor credit them for it. This keeps the
     * expected contribution of unknown state neutral, so pruning world history cannot
     * systematically push a player's score in either direction.
     */
    public double hiddennessWeight() {
        return switch (this) {
            case FULLY_EXPOSED -> 0.0;
            case PARTIALLY_EXPOSED -> 0.25;
            case CONDITIONALLY_EXPOSED -> 0.35;
            case UNKNOWN -> 0.5;
            case HIDDEN -> 1.0;
        };
    }
}
