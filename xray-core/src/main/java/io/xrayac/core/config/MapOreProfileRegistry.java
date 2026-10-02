package io.xrayac.core.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable {@link OreProfileRegistry} backed by a map, plus the ship-with defaults.
 *
 * <p>The default profiles encode the world-generation reasoning documented on {@link OreProfile}.
 * They are the values the plugin uses out of the box, and they are deliberately expressed as
 * conservative priors: they make a legitimate miner's yield look unremarkable, so that the engine
 * needs real accumulation before it can say anything. An administrator whose world differs (a
 * modded pack, an amplified world, a custom ore distribution) tunes these in {@code config.yml}
 * rather than editing code.
 */
public final class MapOreProfileRegistry implements OreProfileRegistry {

    private final Map<String, OreProfile> byId;

    private MapOreProfileRegistry(Map<String, OreProfile> byId) {
        this.byId = Map.copyOf(byId);
    }

    public static MapOreProfileRegistry of(List<OreProfile> profiles) {
        Map<String, OreProfile> map = new LinkedHashMap<>();
        for (OreProfile profile : profiles) {
            OreProfile previous = map.put(profile.oreId(), profile);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate ore profile for id " + profile.oreId());
            }
        }
        return new MapOreProfileRegistry(map);
    }

    @Override
    public Optional<OreProfile> profile(String oreId) {
        return Optional.ofNullable(byId.get(oreId));
    }

    @Override
    public Set<String> enabledOreIds() {
        return byId.values().stream()
                .filter(OreProfile::enabled)
                .map(OreProfile::oreId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public java.util.Collection<OreProfile> profiles() {
        return byId.values();
    }

    /** Builds the block-key to ore-id mapping implied by the given profiles. */
    public static Map<String, String> blockKeyMapping(List<OreProfile> profiles) {
        Map<String, String> mapping = new HashMap<>();
        for (OreProfile profile : profiles) {
            for (String key : profile.blockKeys()) {
                mapping.put(key, profile.oreId());
            }
        }
        return mapping;
    }

    /**
     * The profiles shipped with the plugin.
     *
     * <p>Rationale for the numbers, ore by ore:
     * <ul>
     *   <li><b>Diamond.</b> Deepslate diamond veins are small (typically 1–8 blocks) and clustered;
     *       a legitimate strip-miner working the -59 layer moves on the order of a thousand blocks
     *       of stone per vein found, and a good fraction of those veins are cave-exposed rather
     *       than hidden. The hidden-discovery prior of 1.5 per thousand blocks is therefore
     *       already generous. An ore-informed player, who tunnels straight to every buried vein,
     *       is modelled as finding roughly six times as many buried veins per block moved.</li>
     *   <li><b>Emerald.</b> Rare, in tiny single-block veins, and biome-restricted to mountains.
     *       The prior is much lower and the expected spacing much larger.</li>
     *   <li><b>Ancient debris.</b> Sparse but far more abundant per block than diamond in the
     *       nether, arriving in one-to-three-block clumps; the rate prior is correspondingly
     *       higher and the separation shorter.</li>
     * </ul>
     * The look-alignment baseline of 0.067 for every ore is not an empirical guess: it is the exact
     * fraction of solid angle occupied by a 30-degree cone, {@code (1 - cos 30°)/2}, which is the
     * probability that an undirected heading lands within the cone. Using the geometric value
     * rather than a tuned constant is what makes the targeting model defensible from first
     * principles.
     */
    public static List<OreProfile> defaults() {
        return List.of(
                OreProfile.builder("diamond", "Diamond")
                        .blockKeys("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")
                        .hiddenDiscoveryRatePerThousandBlocks(1.5)
                        .oreInformedRateMultiplier(6.0)
                        .yRange(-64, 16)
                        .typicalVeinSize(4)
                        .expectedDistanceBetweenDiscoveries(400.0)
                        .lookAlignmentProbabilities(0.067, 0.5)
                        .build(),
                OreProfile.builder("emerald", "Emerald")
                        .blockKeys("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore")
                        .hiddenDiscoveryRatePerThousandBlocks(0.35)
                        .oreInformedRateMultiplier(6.0)
                        .yRange(-16, 320)
                        .typicalVeinSize(2)
                        .expectedDistanceBetweenDiscoveries(900.0)
                        .lookAlignmentProbabilities(0.067, 0.5)
                        .build(),
                OreProfile.builder("ancient_debris", "Ancient Debris")
                        .blockKeys("minecraft:ancient_debris")
                        .hiddenDiscoveryRatePerThousandBlocks(2.5)
                        .oreInformedRateMultiplier(4.0)
                        .yRange(8, 119)
                        .typicalVeinSize(2)
                        .expectedDistanceBetweenDiscoveries(300.0)
                        .lookAlignmentProbabilities(0.067, 0.5)
                        .build());
    }
}
