package io.xrayac.core.domain;

/**
 * Identifies a world/dimension.
 *
 * <p>Modelled as an opaque string key rather than a platform world object so that the
 * analytical core never holds a live reference into the server. The key is conventionally
 * {@code <worldName>#<dimension>} (for example {@code survival#minecraft:overworld}) because
 * ore generation, exposure geometry and the exposure analyser's cavity model all differ
 * between the overworld, the nether and the end, and evidence must never be merged across
 * dimensions with incompatible ore distributions.
 */
public record WorldId(String key) {

    public WorldId {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("world key is required");
        }
    }

    public static WorldId of(String key) {
        return new WorldId(key);
    }

    /** The dimension segment of the key, or the whole key when no separator is present. */
    public String dimension() {
        int idx = key.indexOf('#');
        return idx < 0 ? key : key.substring(idx + 1);
    }

    public boolean isNether() {
        return dimension().contains("nether");
    }

    public boolean isEnd() {
        return dimension().contains("the_end") || dimension().contains("end");
    }

    @Override
    public String toString() {
        return key;
    }
}
