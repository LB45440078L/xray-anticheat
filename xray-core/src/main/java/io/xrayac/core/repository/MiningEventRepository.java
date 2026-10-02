package io.xrayac.core.repository;

import io.xrayac.core.domain.Observation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence of individual block breaks.
 *
 * <p>Stored per block because trajectory and tunnel geometry are reconstructed from the sequence of
 * breaks; an aggregate would make that impossible. The cost is volume, which is why this table
 * carries the shortest retention window of any in the schema.
 *
 * <p><b>Threading contract.</b> Blocking I/O; never call from the server thread.
 */
public interface MiningEventRepository {

    /**
     * Persists a batch of mining observations.
     *
     * <p>Batched rather than per-event deliberately: a busy server produces block breaks far faster
     * than one round trip each could be sustained, and batching amortises both the connection
     * acquisition and the statement preparation.
     */
    void saveAll(List<Observation.Mining> events);

    /** Most recent breaks by a player, newest first. */
    List<Observation.Mining> findRecent(UUID playerId, Instant since, int limit);

    /** Blocks broken by a player in a world since a given time. */
    long countSince(UUID playerId, String worldKey, Instant since);

    /**
     * Deletes events older than the cutoff, enforcing retention.
     *
     * @return the number of rows removed
     */
    long deleteOlderThan(Instant cutoff);
}
