package io.xrayac.paper.adapter;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.WorldModificationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * In-memory record of which blocks have been removed, by whom and when.
 *
 * <h2>Why this cannot be backed by the database on demand</h2>
 * The exposure analyser asks "who dug this block, and when?" while running on the Minecraft server
 * thread, because answering it requires reading neighbouring blocks and only the server thread may
 * do that. A database lookup from that context would be a blocking round trip on the main thread —
 * the single hardest rule in this plugin. The ledger therefore lives in memory and is authoritative
 * for reads; the database is a durability record that is written asynchronously and reloaded on
 * startup.
 *
 * <h2>The cost of that choice, stated plainly</h2>
 * The ledger is bounded by {@code ledger-max-entries} and pruned by age. When an entry is evicted,
 * its provenance is forgotten and the analyser will report the block as natural or UNKNOWN rather
 * than guessing. That makes the plugin <i>more lenient</i> about old excavation, never more
 * aggressive, which is the correct direction of error. The alternative — assuming unattributed
 * removals were the current player's — would manufacture hidden-ore evidence out of missing data.
 *
 * <h2>Threading</h2>
 * Writes and reads happen on the server thread; the pruning task and the persistence flush run on
 * worker threads. All map access is synchronised, and reads on the hot path take a short lock on a
 * pure lookup. The pending-write queue is a lock-free {@link ConcurrentLinkedQueue}, so enqueuing
 * from the server thread never contends with the flush worker.
 */
public final class ExcavationLedger {

    /** Composite key: a coordinate only means anything within one world. */
    private record LedgerKey(String worldKey, int x, int y, int z) {

        static LedgerKey of(WorldId world, BlockPos pos) {
            return new LedgerKey(world.key(), pos.x(), pos.y(), pos.z());
        }
    }

    private record LedgerEntry(MiningOrigin origin, UUID actorId, long removedAtEpochMillis) {
    }

    /**
     * A {@link LinkedHashMap} that discards its oldest insertion once the bound is exceeded.
     *
     * <p>Insertion order is the right eviction order here: the entries we can best afford to lose are
     * the oldest removals, whose provenance matters least because the player's approach excavation
     * is by definition recent.
     */
    private static final class BoundedMap extends LinkedHashMap<LedgerKey, LedgerEntry> {
        private static final long serialVersionUID = 1L;
        private final int maxEntries;

        BoundedMap(int maxEntries) {
            super(1024, 0.75f, false);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<LedgerKey, ExcavationLedger.LedgerEntry> eldest) {
            return size() > maxEntries;
        }
    }

    private final Map<LedgerKey, LedgerEntry> entries;
    private final ConcurrentLinkedQueue<WorldModificationRepository.Removal> pendingWrites =
            new ConcurrentLinkedQueue<>();

    public ExcavationLedger(int maxEntries) {
        this.entries = Collections.synchronizedMap(new BoundedMap(maxEntries));
    }

    /**
     * Records a removal.
     *
     * <p>Called on the server thread for every block break, so it does the minimum: one map insert
     * plus one queue offer. Nothing here blocks, allocates heavily, or touches the database.
     */
    public void record(WorldId world, BlockPos pos, MiningOrigin origin, UUID actorId, Instant removedAt) {
        long millis = removedAt.toEpochMilli();
        entries.put(LedgerKey.of(world, pos), new LedgerEntry(origin, actorId, millis));
        pendingWrites.add(new WorldModificationRepository.Removal(world, pos, origin, actorId, removedAt));
        if (pendingWrites.size() > 5_000_000) {
            // A safety valve: if the persistence worker has been unavailable for a very long time,
            // drop the oldest queued rows rather than letting the queue consume the server's memory.
            pendingWrites.poll();
        }
    }

    /** Attribution of the removal at a position, or empty when the ledger has no record. */
    public Optional<MiningOrigin> originAt(WorldId world, BlockPos pos) {
        LedgerEntry entry = entries.get(LedgerKey.of(world, pos));
        return entry == null ? Optional.empty() : Optional.of(entry.origin());
    }

    /** Removal time at a position, or -1 when unrecorded. */
    public long removedAtEpochMillis(WorldId world, BlockPos pos) {
        LedgerEntry entry = entries.get(LedgerKey.of(world, pos));
        return entry == null ? -1L : entry.removedAtEpochMillis();
    }

    /** The player who removed the block, when recorded. */
    public Optional<UUID> actorAt(WorldId world, BlockPos pos) {
        LedgerEntry entry = entries.get(LedgerKey.of(world, pos));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.actorId());
    }

    /**
     * Discards entries older than the cutoff.
     *
     * @return the number of entries removed
     */
    public int pruneOlderThan(Instant cutoff) {
        long cutoffMillis = cutoff.toEpochMilli();
        int removed = 0;
        synchronized (entries) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().removedAtEpochMillis() < cutoffMillis) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Takes up to {@code limit} queued removals for the persistence worker to write.
     *
     * <p>Draining is destructive: once taken, a removal is the worker's responsibility. If the write
     * then fails, that provenance is lost from the durable record — but it is still in memory, so
     * live analysis is unaffected. The failure is logged rather than silently retried forever, since
     * an unbounded retry buffer is its own outage.
     */
    public List<WorldModificationRepository.Removal> drainPending(int limit) {
        List<WorldModificationRepository.Removal> drained = new ArrayList<>(Math.min(limit, 1024));
        for (int i = 0; i < limit; i++) {
            WorldModificationRepository.Removal removal = pendingWrites.poll();
            if (removal == null) {
                break;
            }
            drained.add(removal);
        }
        return drained;
    }

    /** How many removals are awaiting persistence, for status reporting. */
    public int pendingWriteCount() {
        return pendingWrites.size();
    }

    /** How many entries are currently held, for status reporting. */
    public int size() {
        return entries.size();
    }
}
