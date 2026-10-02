package io.xrayac.core.domain;

import io.xrayac.core.geom.BlockPos;

/**
 * The result of analysing one ore block's local topology.
 *
 * <p>Carries not just the verdict but the working, because every conclusion in this plugin
 * must be explainable to a moderator. The counts and flags here are what a moderator reads
 * when they ask "why was this ore counted as hidden?".
 *
 * @param state                 the exposure classification
 * @param openFaces             how many of the six orthogonal neighbours were open/transparent
 * @param cavityAdjacent        whether at least one open neighbour belongs to a natural cavity
 *                              rather than a player excavation
 * @param excavatedAdjacent     whether at least one open neighbour is attributable to
 *                              excavation we can date
 * @param excavationAgeMillis   age of the most recent datable excavation among the neighbours,
 *                              or {@code -1} when no datable excavation is adjacent
 * @param confidence            confidence in the classification, in {@code [0, 1]}; reduced when
 *                              neighbouring chunk data was unavailable
 * @param rationale             a short human-readable account of the classification
 */
public record ExposureResult(
        ExposureState state,
        int openFaces,
        boolean cavityAdjacent,
        boolean excavatedAdjacent,
        long excavationAgeMillis,
        double confidence,
        String rationale) {

    public ExposureResult {
        if (state == null) {
            throw new IllegalArgumentException("exposure state is required");
        }
        if (openFaces < 0 || openFaces > 6) {
            throw new IllegalArgumentException("openFaces must lie in [0, 6], got " + openFaces);
        }
        if (!(confidence >= 0.0) || !(confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence must lie in [0, 1], got " + confidence);
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("rationale is required: evidence must be explainable");
        }
    }

    /** A classification we could not establish because world data was unavailable. */
    public static ExposureResult unknown(BlockPos position, String why) {
        return new ExposureResult(ExposureState.UNKNOWN, 0, false, false, -1L, 0.0,
                "unknown exposure at " + position + ": " + why);
    }

    public boolean isHidden() {
        return state == ExposureState.HIDDEN;
    }
}
