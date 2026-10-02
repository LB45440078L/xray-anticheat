package io.xrayac.core.world;

/**
 * A classification of a block's contribution to visibility and to occupiable space.
 *
 * <p>The exposure model does not care whether a block is stone, deepslate or cobblestone; it
 * cares whether the block <i>blocks sight</i> and whether a player could <i>stand and look
 * from</i> the neighbouring space. Collapsing hundreds of materials into these few
 * visibility-relevant categories is what makes the analyser configurable (an administrator can
 * map any material to any category) without touching code.
 */
public enum BlockKind {

    /** Fully opaque, solid. Blocks sight in every context. */
    OPAQUE_SOLID,

    /**
     * Solid for movement but partially sight-transmitting — glass, panes, some leaves.
     * Such a block can expose an ore (you can see through it) even though you cannot pass.
     */
    TRANSPARENT_SOLID,

    /** Empty space. Both sight-transmitting and occupiable. */
    AIR,

    /** Water or lava. Sight-transmitting and occupiable (water), so it can expose an ore. */
    FLUID,

    /**
     * Non-solid decoration: torches, rails, fences, signs, plants. Sight-transmitting; not
     * occupiable, so it cannot itself host a vantage point, but it does not hide anything.
     */
    NON_SOLID_DECORATION,

    /** The material is unknown to the configured mapping, or the chunk was unreadable. */
    UNKNOWN;

    /**
     * Whether this kind of block allows an observer to see past it (and thus whether a face
     * bounded by it counts as an "open face" for exposure purposes).
     */
    public boolean transmitsVisibility() {
        return switch (this) {
            case AIR, FLUID, NON_SOLID_DECORATION, TRANSPARENT_SOLID -> true;
            case OPAQUE_SOLID, UNKNOWN -> false;
        };
    }

    /**
     * Whether a player could physically occupy this space and therefore use it as a vantage
     * point. Decoration is sight-transmitting but not occupiable, which is why this is a
     * distinct question from {@link #transmitsVisibility()}.
     */
    public boolean providesStandingRoom() {
        return switch (this) {
            case AIR, FLUID -> true;
            case OPAQUE_SOLID, TRANSPARENT_SOLID, NON_SOLID_DECORATION, UNKNOWN -> false;
        };
    }
}
