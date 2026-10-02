package io.xrayac.core.world;

import io.xrayac.core.port.OreCatalog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable {@link OreCatalog} built from a block-key to ore-id mapping.
 *
 * <p>Platform-neutral, so it is used both by the Paper adapter (constructed from {@code config.yml})
 * and by unit tests (constructed inline). Immutable after construction and safe for concurrent
 * reads from analysis workers.
 */
public final class MapOreCatalog implements OreCatalog {

    private final Map<String, String> blockToOre;
    private final Set<String> oreIds;

    private MapOreCatalog(Map<String, String> blockToOre) {
        this.blockToOre = Map.copyOf(blockToOre);
        this.oreIds = Set.copyOf(new LinkedHashSet<>(blockToOre.values()));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<String> oreIdFor(String blockKey) {
        if (blockKey == null) {
            return Optional.empty();
        }
        String direct = blockToOre.get(blockKey);
        if (direct != null) {
            return Optional.of(direct);
        }
        // Tolerate keys supplied without the namespace (for example "diamond_ore"), which is how
        // administrators often write them in configuration. The namespaced form always wins.
        if (!blockKey.contains(":")) {
            return Optional.ofNullable(blockToOre.get("minecraft:" + blockKey));
        }
        return Optional.empty();
    }

    @Override
    public Set<String> knownOreIds() {
        return oreIds;
    }

    /** Mutable builder; not thread-safe, and only used during startup. */
    public static final class Builder {

        private final Map<String, String> mapping = new HashMap<>();

        /**
         * Maps a block key to a canonical ore id.
         *
         * @throws IllegalArgumentException on a blank key, or when the same block key is mapped
         *         to two different ores (almost always a configuration mistake worth surfacing
         *         at startup rather than at first use)
         */
        public Builder map(String blockKey, String oreId) {
            if (blockKey == null || blockKey.isBlank() || oreId == null || oreId.isBlank()) {
                throw new IllegalArgumentException("block key and ore id must be non-blank");
            }
            String existing = mapping.put(blockKey, oreId);
            if (existing != null && !existing.equals(oreId)) {
                throw new IllegalArgumentException("block " + blockKey
                        + " is mapped to both '" + existing + "' and '" + oreId + "'");
            }
            return this;
        }

        /** Maps several block keys to the same ore id. */
        public Builder mapAll(String oreId, Iterable<String> blockKeys) {
            for (String key : blockKeys) {
                map(key, oreId);
            }
            return this;
        }

        public MapOreCatalog build() {
            return new MapOreCatalog(new LinkedHashMap<>(mapping));
        }
    }
}
