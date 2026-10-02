package io.xrayac.core.domain;

/**
 * Attribution of a mined block or an adjacent opening to whoever created it.
 *
 * <p>This is the mechanism that prevents multiplayer contamination. If the opening beside an
 * ore was made by <i>someone else</i>, then the player who later walked in and mined it did
 * not need X-ray to find it — they found a hole somebody else dug. Without this distinction a
 * server with even casual cooperation would generate false accusations against players who
 * simply explore inhabited tunnels.
 */
public enum MiningOrigin {

    /** The observed player removed this block. */
    PLAYER_CREATED,

    /**
     * Another player removed this block. The current player may legitimately have found the
     * resulting opening by exploring; evidence must be heavily discounted.
     */
    OTHER_PLAYER_CREATED,

    /**
     * The block is part of naturally generated terrain (a cave, ravine, water, lava or other
     * naturally occurring opening). Exposure through natural terrain is the legitimate case.
     */
    NATURAL_TERRAIN,

    /**
     * The block was removed by something that is not a player — world-generation structures,
     * explosions, other plugins, or server-side terrain edits. Treated as non-evidence.
     */
    ENVIRONMENTAL,

    /** We cannot attribute this modification. Carries no evidentiary weight by itself. */
    UNKNOWN;

    /**
     * Whether an opening of this origin could plausibly have been perceived by a player
     * exploring normally. Only {@link #NATURAL_TERRAIN} and unknown/unattributed openings
     * count; territories we know were dug by others do not, because the player's discovery is
     * then explained without invoking X-ray.
     */
    public boolean explainsDiscoveryWithoutXray() {
        return this == NATURAL_TERRAIN || this == ENVIRONMENTAL || this == UNKNOWN;
    }
}
