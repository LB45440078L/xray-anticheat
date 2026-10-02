package io.xrayac.core.repository;

import io.xrayac.core.evidence.SuspicionSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of suspicion assessments.
 *
 * <p>The full snapshot is stored — including the decomposition into prior, effective and posterior
 * log-odds and every component's contribution — rather than only the final score. Two reasons.
 * First, auditability: months later, an administrator must be able to see <i>why</i> the system
 * reached a conclusion, not just what the number was. Second, replay: the same frozen observations
 * can be re-evaluated against a revised policy without re-reading the world.
 *
 * <p><b>Threading contract.</b> Blocking I/O; never call from the server thread.
 */
public interface SuspicionRepository {

    /**
     * Persists a snapshot and its contributing evidence atomically.
     *
     * @return the generated identifier of the stored snapshot
     */
    String save(SuspicionSnapshot snapshot);

    /** Most recent snapshots for a player, newest first. */
    List<SuspicionSnapshot> history(UUID playerId, int limit);

    /** The most recent snapshot for a player, if any. */
    Optional<SuspicionSnapshot> latest(UUID playerId);

    /**
     * Deletes snapshots older than the cutoff, cascading to their evidence rows.
     *
     * @return the number of snapshot rows removed
     */
    long deleteOlderThan(java.time.Instant cutoff);
}
