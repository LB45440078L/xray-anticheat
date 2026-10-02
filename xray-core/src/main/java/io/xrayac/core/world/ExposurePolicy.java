package io.xrayac.core.world;

/**
 * Tunable parameters of the exposure model.
 *
 * <p>Kept as a small immutable policy object rather than a slice of the global configuration so
 * that the analyser's behaviour is fully determined by its arguments, which is what makes it
 * testable and what makes the administrative configuration auditable.
 *
 * @param diagonalVisibility            whether a diagonal opening (through a corner) counts as
 *                                      partial exposure. Off by default: in vanilla, corner
 *                                      peeking through a single diagonal gap is possible but
 *                                      awkward, and enabling it too eagerly would classify
 *                                      genuinely hidden ores as visible.
 * @param recentExcavationWindowMillis  openings created <b>by the observing player</b> within
 *                                      this many milliseconds before the observation are
 *                                      treated as the player's own approach excavation and do
 *                                      not count as pre-existing exposure. This is the
 *                                      mechanism that stops "player dug to the ore" from being
 *                                      misread as "ore was visible".
 * @param occupiedSpaceRadius           how far from the ore we search for pre-existing
 *                                      occupiable space when deciding partial exposure.
 * @param excavationAttributionRequired when true, an opening whose origin cannot be attributed
 *                                      (UNKNOWN) is not counted as natural exposure, and the
 *                                      result is reported with reduced confidence instead.
 */
public record ExposurePolicy(
        boolean diagonalVisibility,
        long recentExcavationWindowMillis,
        int occupiedSpaceRadius,
        boolean excavationAttributionRequired) {

    public ExposurePolicy {
        if (recentExcavationWindowMillis < 0) {
            throw new IllegalArgumentException("recentExcavationWindowMillis must be >= 0");
        }
        if (occupiedSpaceRadius < 1 || occupiedSpaceRadius > 3) {
            // Beyond a 3-block radius the "could the player have seen it" question stops being
            // meaningful: a large cavern means the ore is exposed anyway, and scanning further
            // only multiplies cost.
            throw new IllegalArgumentException("occupiedSpaceRadius must lie in [1, 3]");
        }
    }

    /** Sensible defaults for an overworld survival server. */
    public static ExposurePolicy defaults() {
        return new ExposurePolicy(false, 90_000L, 1, false);
    }
}
