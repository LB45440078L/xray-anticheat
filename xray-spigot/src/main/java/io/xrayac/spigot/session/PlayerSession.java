package io.xrayac.spigot.session;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded, in-memory state for one player in one world.
 *
 * <h2>Threading</h2>
 * Every mutating method and every read is called on the Minecraft server thread, and only there.
 * The window handed to the analysis worker is an immutable {@link PlayerAnalysisWindow}, so the
 * worker never touches this object. That single rule — one thread owns the session, and workers
 * receive frozen copies — is what removes the need for any lock in the hottest code path in the
 * plugin. Adding a lock here would be worse than useless: it would suggest the class is shared when
 * it deliberately is not.
 *
 * <h2>Bounded by construction</h2>
 * Every buffer discards its oldest entry on overflow. Minecraft servers run for months; a tracker
 * that accumulates per block break would exhaust memory on any real server, so the bound is not an
 * optimisation but a correctness requirement. When an entry is discarded, the oldest evidence is
 * lost and the window becomes slightly shallower — which makes the system more cautious, never more
 * aggressive.
 */
public final class PlayerSession {

    private final PlayerRef player;
    private final WorldId world;
    private final int pathBufferSize;
    private final int miningBufferSize;
    private final int discoveryBufferSize;

    private final Deque<TrajectoryAnalysis.PathPoint> path = new ArrayDeque<>();
    private final Deque<Observation.Mining> miningEvents = new ArrayDeque<>();
    private final Deque<OreDiscovery> discoveries = new ArrayDeque<>();

    private final Instant windowStart;

    private double blocksMined;
    private double distanceTravelled;
    private double blocksSinceLastDiscovery;
    private double distanceSinceLastDiscovery;

    /**
     * Blocks belonging to veins already recorded as discoveries.
     *
     * <p>Without this, breaking the several blocks of one vein produces several discoveries. That is
     * both an inflated sample and a systematic bias: the first block broken may be an enclosed one
     * while the rest of the vein is in the open, and every subsequent break would be classified
     * "hidden" because the player's own earlier breaks are now the adjacent openings. One vein is one
     * observation; this set is what enforces it.
     *
     * <p>Bounded by the mining-buffer size. When the bound is reached the oldest entries are dropped,
     * which can at worst re-count a vein long after it was mined — and only if the player returns to
     * the same spot, which the sequence of events makes unlikely.
     */
    private final Set<BlockPos> accountedVeinBlocks = new LinkedHashSet<>();

    private Instant lastActivity;
    private Vector3 lastSamplePosition;
    private Vector3 lastKnownPosition;

    /**
     * A session with no recorded position yet.
     *
     * <p>{@link #recordMovement} is null-safe about the previous position, so the first movement of a
     * session contributes no travelled distance. That is deliberate: without it, the hop from wherever
     * a player logged in to their first observed position would be counted as travel and would inflate
     * the distance-based rates for every session by one arbitrary jump.
     */
    public PlayerSession(PlayerRef player, WorldId world, Instant startedAt,
                         int pathBufferSize, int miningBufferSize, int discoveryBufferSize) {
        this.player = player;
        this.world = world;
        this.windowStart = startedAt;
        this.lastActivity = startedAt;
        this.pathBufferSize = pathBufferSize;
        this.miningBufferSize = miningBufferSize;
        this.discoveryBufferSize = discoveryBufferSize;
    }

    public PlayerRef player() {
        return player;
    }

    public WorldId world() {
        return world;
    }

    public Instant windowStart() {
        return windowStart;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    /**
     * Records a movement.
     *
     * <p>Distance is accumulated from every move event, but a path point is stored only once the
     * player has moved at least {@code sampleThreshold} from the last stored point. Separating the two
     * matters: a player who jitters under the threshold still accrues real travelled distance — the
     * denominator of several rate calculations — while the path buffer stays small enough to hold
     * many minutes of motion.
     *
     * @param to              the position after the move
     * @param at              when the move happened
     * @param lookDirection   the player's view direction, or null when unavailable
     * @param sampleThreshold minimum distance before a new path point is stored
     */
    public void recordMovement(Vector3 to, Instant at, Vector3 lookDirection, double sampleThreshold) {
        lastActivity = at;

        if (lastKnownPosition != null) {
            double delta = lastKnownPosition.distanceTo(to);
            distanceTravelled += delta;
            distanceSinceLastDiscovery += delta;
        }
        lastKnownPosition = to;

        if (lastSamplePosition == null || lastSamplePosition.distanceTo(to) >= sampleThreshold) {
            path.addLast(new TrajectoryAnalysis.PathPoint(to, at, lookDirection));
            trimTo(path, pathBufferSize);
            lastSamplePosition = to;
        }
    }

    /**
     * Records a block break, whether or not it was ore.
     *
     * <p>Non-ore breaks matter as much as ore breaks: they are the denominator of the discovery rate.
     * "This player found eleven buried veins" means nothing without "out of how much rock".
     */
    public void recordMining(Observation.Mining event) {
        lastActivity = event.timestamp();
        blocksMined++;
        blocksSinceLastDiscovery++;

        miningEvents.addLast(event);
        trimTo(miningEvents, miningBufferSize);
    }

    /** Records a reconstructed ore discovery and resets the intervening-effort counters. */
    public void recordDiscovery(OreDiscovery discovery) {
        discoveries.addLast(discovery);
        trimTo(discoveries, discoveryBufferSize);
        blocksSinceLastDiscovery = 0.0;
        distanceSinceLastDiscovery = 0.0;
    }

    /** The blocks mined since the previous discovery, used to build the next discovery's interval. */
    public double blocksSinceLastDiscovery() {
        return blocksSinceLastDiscovery;
    }

    public double distanceSinceLastDiscovery() {
        return distanceSinceLastDiscovery;
    }

    /** Whether this block belongs to a vein already recorded as a discovery. */
    public boolean isVeinAccounted(BlockPos seed) {
        return accountedVeinBlocks.contains(seed);
    }

    /** Marks every block of a vein as accounted for, so further breaks of it create no discovery. */
    public void accountVein(Set<BlockPos> veinBlocks) {
        accountedVeinBlocks.addAll(veinBlocks);
        while (accountedVeinBlocks.size() > miningBufferSize) {
            var iterator = accountedVeinBlocks.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public double blocksMined() {
        return blocksMined;
    }

    public double distanceTravelled() {
        return distanceTravelled;
    }

    public int pathPointCount() {
        return path.size();
    }

    public int discoveryCount() {
        return discoveries.size();
    }

    /**
     * Whether this session holds anything worth analysing.
     *
     * <p>A player who has been standing still has produced nothing; submitting them for analysis
     * every interval would spend worker time to conclude nothing and would also record a stored
     * assessment of "no evidence", cluttering the audit trail with non-events.
     */
    public boolean hasAnalysableActivity() {
        return blocksMined > 0 || !discoveries.isEmpty();
    }

    /** The path buffer, oldest first, for the geometry engine. */
    public List<TrajectoryAnalysis.PathPoint> pathPoints() {
        return new ArrayList<>(path);
    }

    /** The mining events, oldest first, for trajectory reconstruction. */
    public List<Observation.Mining> miningEvents() {
        return new ArrayList<>(miningEvents);
    }

    /** The discoveries, oldest first. */
    public List<OreDiscovery> discoveryList() {
        return new ArrayList<>(discoveries);
    }

    /**
     * Freezes the current state into an immutable window for a worker thread.
     *
     * <p>After this call the worker owns a self-contained snapshot; the session may continue to be
     * mutated on the server thread without any risk of the worker observing a half-updated player.
     */
    public PlayerAnalysisWindow snapshot(Instant windowEnd) {
        // The path travels with the window rather than a geometry computed here, so the analysis worker can
        // merge it with the mining path rebuilt from stored history and have the tunnel-geometry signal
        // describe a player's whole recorded time instead of just this login. Geometry is derived from the
        // path where it is needed.
        return PlayerAnalysisWindow.of(player, world, windowStart, windowEnd,
                blocksMined, distanceTravelled, discoveryList(), pathPoints());
    }

    private static <T> void trimTo(Deque<T> deque, int maxSize) {
        while (deque.size() > maxSize) {
            deque.pollFirst();
        }
    }
}
