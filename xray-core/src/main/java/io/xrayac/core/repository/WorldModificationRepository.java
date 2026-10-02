package io.xrayac.core.repository;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The excavation ledger: which blocks were removed, by whom, and when.
 *
 * <p>This repository is what makes the exposure analyser's central question answerable — "could this
 * player have seen this ore before they dug to it, or did somebody else open the way?" Without a
 * record of removals, every ore is either surrounded by stone (looks hidden, unfairly flagging
 * strip-miners) or adjacent to air (looks exposed, missing everything). The ledger supplies the
 * third piece of information: <i>provenance</i>.
 *
 * <p>Reads are exact-coordinate lookups on the analysis path and must stay fast; the implementation
 * is expected to index on (world, x, y, z).
 *
 * <p><b>Threading contract.</b> Blocking I/O; never call from the server thread.
 */
public interface WorldModificationRepository {

    /**
     * Records that a block was removed.
     *
     * @param origin  attribution of the removal
     * @param actorId the player responsible, when known
     */
    void record(WorldId world, BlockPos pos, MiningOrigin origin, UUID actorId, Instant removedAt);

    /** Records many removals in one batch, which is the normal path for bulk persistence. */
    void recordAll(java.util.List<Removal> removals);

    /**
     * Attribution of the removal at a position, or empty when there is no record.
     *
     * <p>Returning empty is a meaningful and common answer: it means we have no history for that
     * block, either because it was never removed or because the record has been pruned. The analyser
     * treats the two cases conservatively and distinctly, which is why this is not collapsed into a
     * default {@link MiningOrigin}.
     */
    Optional<MiningOrigin> originAt(WorldId world, BlockPos pos);

    /** Wall-clock removal time at a position, or -1 when unrecorded. */
    long removedAtEpochMillis(WorldId world, BlockPos pos);

    /**
     * Deletes ledger entries older than the given instant, enforcing the retention policy.
     *
     * @return the number of rows removed
     */
    long deleteOlderThan(Instant cutoff);

    /** Counts ledger rows, for diagnostics and status reporting. */
    long count();

    /**
     * One removal, as a value object for batch writes.
     */
    record Removal(WorldId world, BlockPos pos, MiningOrigin origin, UUID actorId, Instant removedAt) {
    }
}
