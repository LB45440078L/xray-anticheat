package io.xrayac.core.repository;

import io.xrayac.core.analysis.OreDiscovery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence of ore vein encounters.
 *
 * <p>One row per <b>vein</b>, not per ore block: a vein is one statistical observation, and storing
 * its blocks individually would both inflate the sample size and multiply storage by the vein size.
 *
 * <p>These rows are also the data source for the Python visualiser, which is why the full geometric
 * and alignment context is stored rather than only the verdict — a moderator inspecting a suspicious
 * player needs to see where the player was heading, not merely that the system disapproved.
 *
 * <p><b>Threading contract.</b> Blocking I/O; never call from the server thread.
 */
public interface OreDiscoveryRepository {

    /**
     * Persists a discovery.
     *
     * <p>The world is passed explicitly rather than being read from the {@link OreDiscovery}: a
     * discovery is a value object scoped to a vein, while the world is a property of the analysis
     * window it came from. Threading it through explicitly keeps that scoping visible instead of
     * hiding it in an overload.
     *
     * @param sessionId the play session, or {@code null} when the caller does not track sessions
     */
    void save(UUID playerId, String worldKey, String sessionId, OreDiscovery discovery);

    /** Recent discoveries by a player, newest first. */
    List<StoredDiscovery> findRecent(UUID playerId, Instant since, int limit);

    /** Discoveries in a world of a given ore, used for cross-player comparison. */
    List<StoredDiscovery> findByWorldAndOre(String worldKey, String oreId, Instant since, int limit);

    /**
     * Deletes discoveries older than the cutoff.
     *
     * @return the number of rows removed
     */
    long deleteOlderThan(Instant cutoff);

    /**
     * A persisted discovery, carrying the row identity and player association that the in-memory
     * {@link OreDiscovery} does not need.
     */
    record StoredDiscovery(
            String id,
            UUID playerId,
            String worldKey,
            OreDiscovery discovery) {
    }
}
