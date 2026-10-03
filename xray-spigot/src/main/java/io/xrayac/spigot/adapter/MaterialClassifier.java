package io.xrayac.spigot.adapter;

import io.xrayac.core.world.BlockKind;
import org.bukkit.Material;

/**
 * Maps a Minecraft material onto the small set of visibility categories the exposure analyser cares
 * about.
 *
 * <h2>Why this uses material properties rather than a hand-written list</h2>
 * The analyser needs to know only two things about a block: whether it blocks sight, and whether a
 * player could stand in it. Bukkit already exposes both — {@code isOccluding()} and
 * {@code isSolid()} — and those flags are maintained by the server for every material, including
 * ones added by future game versions and by data packs. A hard-coded list of several hundred
 * materials would go stale the first time Mojang adds a block, and its failure mode would be silent:
 * a new transparent block would be misread as opaque, and ore behind it would be classified as
 * hidden. Deriving from server-provided properties means new blocks are handled correctly by
 * default.
 *
 * <h2>The one thing properties cannot express</h2>
 * Air and fluids are both sight-transmitting <i>and</i> occupiable, but they are not interchangeable
 * for the analyser: fluid can expose an ore, while only air actually lets a player stand and look
 * from a vantage point. The distinction matters to
 * {@link io.xrayac.core.world.ExposureAnalyzer}'s vantage-point search, so fluids are separated out
 * explicitly by name. Everything else falls out of the two property checks.
 */
public final class MaterialClassifier {

    /**
     * Classifies a material.
     *
     * <p>Order matters: air is checked before occlusion, because air is neither solid nor occluding
     * and would otherwise fall through to be classified as decoration.
     */
    public BlockKind classify(Material material) {
        if (material == null) {
            return BlockKind.UNKNOWN;
        }
        if (material.isAir()) {
            return BlockKind.AIR;
        }
        if (isFluid(material)) {
            return BlockKind.FLUID;
        }
        if (material.isOccluding()) {
            return BlockKind.OPAQUE_SOLID;
        }
        if (material.isSolid()) {
            // Solid but not occluding: glass, panes, some leaves. You cannot walk through it but you
            // can see through it, so it exposes an ore without providing a vantage point.
            return BlockKind.TRANSPARENT_SOLID;
        }
        // Non-solid and non-occluding: torches, rails, fences, signs, plants. Sight passes through;
        // nobody stands in it.
        return BlockKind.NON_SOLID_DECORATION;
    }

    /**
     * Whether the material is a fluid.
     *
     * <p>Detected by name rather than by a {@code Material} subtype check, because Bukkit's material
     * enum has varied in how it represents fluids across versions and there is no stable
     * {@code isFluid()} predicate on {@code Material} itself.
     */
    private static boolean isFluid(Material material) {
        String name = material.name();
        return name.equals("WATER") || name.equals("LAVA") || name.endsWith("_WATER")
                || name.endsWith("_LAVA") || name.equals("BUBBLE_COLUMN");
    }

    /** The stable, namespaced key of a material, used as the ore catalogue's lookup key. */
    public String blockKey(Material material) {
        if (material == null) {
            return "minecraft:air";
        }
        return material.getKey().toString();
    }
}
