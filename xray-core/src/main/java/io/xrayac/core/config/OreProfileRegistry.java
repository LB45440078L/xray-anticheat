package io.xrayac.core.config;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Lookup of ore profiles by canonical ore id.
 *
 * <p>A port rather than a concrete map so the Paper adapter can build it from {@code config.yml}
 * while the analysis engine, and every unit test, sees the same interface.
 *
 * <p>Implementations must be safe for concurrent reads.
 */
public interface OreProfileRegistry {

    Optional<OreProfile> profile(String oreId);

    Collection<OreProfile> profiles();

    /** Ore ids that are enabled for analysis. */
    Set<String> enabledOreIds();

    /** Whether the given ore id is enabled. */
    default boolean isEnabled(String oreId) {
        return profile(oreId).filter(OreProfile::enabled).isPresent();
    }
}
