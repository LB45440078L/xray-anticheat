package io.xrayac.spigot.persistence;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.domain.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queues observations on the server thread for a worker to write later.
 *
 * <h2>Why a queue is the whole point</h2>
 * The server thread may not perform a database write, so it must not even attempt one. Every
 * observation therefore lands in a lock-free queue (an offer costs nanoseconds and never blocks) and a
 * scheduled worker drains it in batches. This is the mechanism that satisfies the plugin's hardest
 * rule while still recording everything.
 *
 * <h2>Bounded, and honest about dropping</h2>
 * If the database is down for a long time the queue would otherwise grow without limit and turn a
 * storage outage into a memory outage. It is bounded, and when the bound is reached the oldest entries
 * are discarded. Dropping is the lesser evil — but it is <b>counted</b>, and the count is surfaced, so
 * an administrator sees that the evidence model is operating on incomplete data rather than believing
 * a quiet log means everything is fine.
 */
public final class PendingPersistence {

    /** A discovery, paired with the identity the repository needs to store it. */
    public record DiscoveryRecord(UUID playerId, String worldKey, OreDiscovery discovery) {
    }

    private final ConcurrentLinkedQueue<Observation.Mining> mining = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DiscoveryRecord> discoveries = new ConcurrentLinkedQueue<>();
    private final int maxQueued;

    private final AtomicLong droppedMining = new AtomicLong();
    private final AtomicLong droppedDiscoveries = new AtomicLong();

    public PendingPersistence(int maxQueued) {
        this.maxQueued = maxQueued;
    }

    /** Called on the server thread for every block break. Never blocks. */
    public void enqueueMining(Observation.Mining event) {
        if (mining.size() >= maxQueued) {
            // Discard the oldest rather than the newest: the most recent observations are the ones
            // the analysis is actively using, and losing them would leave the live picture wrong
            // while the durable record looked superficially complete.
            mining.poll();
            droppedMining.incrementAndGet();
        }
        mining.add(event);
    }

    /** Called on the server thread for every ore discovery. Never blocks. */
    public void enqueueDiscovery(UUID playerId, String worldKey, OreDiscovery discovery) {
        if (discoveries.size() >= maxQueued) {
            discoveries.poll();
            droppedDiscoveries.incrementAndGet();
        }
        discoveries.add(new DiscoveryRecord(playerId, worldKey, discovery));
    }

    /** Takes up to {@code limit} mining events for the writer. */
    public List<Observation.Mining> drainMining(int limit) {
        return drain(mining, limit);
    }

    /** Takes up to {@code limit} discoveries for the writer. */
    public List<DiscoveryRecord> drainDiscoveries(int limit) {
        return drain(discoveries, limit);
    }

    private static <T> List<T> drain(ConcurrentLinkedQueue<T> queue, int limit) {
        List<T> drained = new ArrayList<>(Math.min(limit, 1024));
        for (int i = 0; i < limit; i++) {
            T item = queue.poll();
            if (item == null) {
                break;
            }
            drained.add(item);
        }
        return drained;
    }

    public int queuedMining() {
        return mining.size();
    }

    public int queuedDiscoveries() {
        return discoveries.size();
    }

    public int queuedTotal() {
        return queuedMining() + queuedDiscoveries();
    }

    /** Observations discarded because the queue was full or a write failed. */
    public long droppedMiningCount() {
        return droppedMining.get();
    }

    public long droppedDiscoveryCount() {
        return droppedDiscoveries.get();
    }

    /** Records that a drained batch could not be written, so the loss is visible. */
    public void recordDropped(int miningCount, int discoveryCount) {
        droppedMining.addAndGet(miningCount);
        droppedDiscoveries.addAndGet(discoveryCount);
    }
}
