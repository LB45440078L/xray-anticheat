package io.xrayac.core.port;

import java.util.Optional;
import java.util.Set;

/**
 * Maps raw block identifiers onto canonical ore identifiers.
 *
 * <p>An outbound port because "which materials count as the ore {@code diamond}" is a server
 * configuration decision, not a fact the core can assume. A server running a modpack, or one
 * that renames materials, must be able to redefine the mapping without a code change; equally,
 * the core must be able to treat {@code minecraft:diamond_ore} and
 * {@code minecraft:deepslate_diamond_ore} as the same ore for statistical purposes while still
 * recording which specific block was broken.
 *
 * <p>Implementations must be immutable or effectively immutable after construction and safe for
 * concurrent reads, because the catalogue is consulted from analysis workers.
 */
public interface OreCatalog {

    /**
     * The canonical ore identifier for a block key, or empty when the block is not an ore this
     * server analyses.
     */
    Optional<String> oreIdFor(String blockKey);

    /** Every canonical ore identifier known to this catalogue. */
    Set<String> knownOreIds();

    /** Whether the given block key belongs to any analysed ore. */
    default boolean isOre(String blockKey) {
        return oreIdFor(blockKey).isPresent();
    }

    /** Whether two block keys belong to the same canonical ore. */
    default boolean sameOre(String a, String b) {
        Optional<String> idA = oreIdFor(a);
        return idA.isPresent() && idA.equals(oreIdFor(b));
    }
}
