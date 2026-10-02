package io.xrayac.core.analysis;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.statistics.LikelihoodRatios;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An immutable, self-contained summary of everything observed about one player within one
 * analysis window.
 *
 * <p>This is the <b>unit of work</b> handed to the statistical engine. Making it immutable and
 * detached from live server state is what allows the entire analysis to run on a worker thread
 * with no locks and no risk of observing a half-updated player: the server thread collects
 * observations into a window, freezes it, and hands the frozen copy over. Everything downstream
 * reads only this object.
 *
 * <p>A window is scoped to a single world, because ore distributions and exposure geometry are
 * world-specific; evidence from the nether and the overworld must never be pooled.
 *
 * <h2>The path, not a pre-computed geometry</h2>
 * The window carries the trajectory {@link #pathPoints()} and derives {@link #geometry()} from it,
 * rather than carrying a geometry that was computed once at snapshot time. There is then exactly one
 * representation of the trajectory, which is what makes it possible to merge a session's path with
 * the mining path rebuilt from stored history ({@link HistoryHydrator}) and have the tunnel-geometry
 * signal describe the whole span instead of just the current login.
 *
 * @param liveDiscoveryIndex index in {@link #discoveries()} at which this session's own discoveries
 *                           begin, or {@code -1} when the list is drawn from a single continuous
 *                           series. It exists because the stretch between the last stored discovery and
 *                           the first discovery of a new session is <b>unobservable</b>: the server may
 *                           have been down, or the player may have played without being recorded. See
 *                           {@link #hiddenDiscoveryIntervals()} for how that gap is treated.
 */
public record PlayerAnalysisWindow(
        PlayerRef player,
        WorldId world,
        Instant windowStart,
        Instant windowEnd,
        double blocksMined,
        double distanceTravelled,
        List<OreDiscovery> discoveries,
        List<TrajectoryAnalysis.PathPoint> pathPoints,
        int liveDiscoveryIndex) {

    public PlayerAnalysisWindow {
        if (player == null || world == null || windowStart == null || windowEnd == null) {
            throw new IllegalArgumentException("a window requires a player, a world and a time span");
        }
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("window end precedes window start");
        }
        if (blocksMined < 0 || distanceTravelled < 0) {
            throw new IllegalArgumentException("mined/travelled totals cannot be negative");
        }
        if (discoveries == null || pathPoints == null) {
            throw new IllegalArgumentException("discovery and path collections must be present");
        }
        if (liveDiscoveryIndex < -1 || liveDiscoveryIndex > discoveries.size()) {
            throw new IllegalArgumentException(
                    "liveDiscoveryIndex must be -1 or a valid index into discoveries");
        }
        discoveries = List.copyOf(discoveries);
        pathPoints = List.copyOf(pathPoints);
    }

    /**
     * A window assembled from one continuous series, which is the case for a live session and for a
     * history that is analysed on its own.
     */
    public static PlayerAnalysisWindow of(PlayerRef player, WorldId world, Instant start, Instant end,
                                          double blocksMined, double distanceTravelled,
                                          List<OreDiscovery> discoveries,
                                          List<TrajectoryAnalysis.PathPoint> pathPoints) {
        return new PlayerAnalysisWindow(player, world, start, end, blocksMined, distanceTravelled,
                discoveries, pathPoints, -1);
    }

    /** An empty window, used when a player has been observed but nothing analysable happened. */
    public static PlayerAnalysisWindow empty(PlayerRef player, WorldId world,
                                            Instant start, Instant end) {
        return of(player, world, start, end, 0, 0, List.of(), List.of());
    }

    /**
     * The geometry of {@link #pathPoints()}, derived on demand.
     *
     * <p>Computed rather than stored so that the path stays the single source of truth. The
     * decomposition runs once per assessment, in the same place it used to run when the geometry was
     * frozen at snapshot time.
     */
    public TrajectoryAnalysis.Geometry geometry() {
        return pathPoints.size() >= 2
                ? TrajectoryAnalysis.geometry(pathPoints)
                : TrajectoryAnalysis.Geometry.empty();
    }

    public Duration duration() {
        return Duration.between(windowStart, windowEnd);
    }

    public List<OreDiscovery> hiddenDiscoveries() {
        return discoveries.stream().filter(OreDiscovery::isHiddenDiscovery).toList();
    }

    public List<OreDiscovery> exposedDiscoveries() {
        return discoveries.stream().filter(d -> !d.isHiddenDiscovery()).toList();
    }

    public int totalDiscoveries() {
        return discoveries.size();
    }

    public int hiddenCount() {
        return (int) discoveries.stream().filter(OreDiscovery::isHiddenDiscovery).count();
    }

    public int exposedCount() {
        return totalDiscoveries() - hiddenCount();
    }

    /** True when this window merged stored history with the live session. */
    public boolean includesHistory() {
        return liveDiscoveryIndex > 0;
    }

    /** Discoveries (hidden and exposed) keyed by canonical ore id. */
    public Map<String, List<OreDiscovery>> byOre() {
        return discoveries.stream().collect(Collectors.groupingBy(OreDiscovery::oreId));
    }

    public List<OreDiscovery> discoveriesOf(String oreId) {
        return discoveries.stream().filter(d -> d.oreId().equals(oreId)).toList();
    }

    public int hiddenCountOf(String oreId) {
        return (int) discoveries.stream()
                .filter(d -> d.oreId().equals(oreId) && d.isHiddenDiscovery())
                .count();
    }

    public int exposedCountOf(String oreId) {
        return (int) discoveries.stream()
                .filter(d -> d.oreId().equals(oreId) && !d.isHiddenDiscovery())
                .count();
    }

    /**
     * The exposure (blocks mined) between successive hidden-ore discoveries, as interval
     * observations for the exponential waiting-time model.
     *
     * <p>The final interval is treated as <b>right-censored</b>: the window ends with the player
     * having mined {@code blocksMined - lastDiscoveryEffort} blocks so far without another
     * discovery. Dropping that trailing interval would systematically shorten the observed
     * waiting times and bias the model toward suspicion, so it is included as a censored
     * observation. When there are no discoveries at all, the entire window's mining effort is a
     * single censored observation.
     *
     * <p><b>The session boundary is dropped, not guessed.</b> When history has been merged, the first
     * discovery of the current session is separated from the last stored one by a stretch of unknown
     * length — the server may have been down for a week, or the player may have played on a day the
     * plugin was disabled. That discovery's own effort figure measures only the session's mining, so
     * counting it as a complete waiting time would understate a legitimate interval and push the
     * verdict toward suspicion. It is therefore excluded, along with its effort in the trailing
     * censored total, which can only lengthen that censored interval and so can only argue in the
     * player's favour. Uncertainty is never allowed to become evidence against a player.
     */
    public List<LikelihoodRatios.IntervalObservation> hiddenDiscoveryIntervals() {
        List<LikelihoodRatios.IntervalObservation> intervals = new ArrayList<>();
        double consumed = 0.0;
        int hiddenSeen = 0;

        for (int index = 0; index < discoveries.size(); index++) {
            if (index == liveDiscoveryIndex) {
                // Unobservable gap: restart the series here without emitting an interval for it.
                consumed = 0.0;
                continue;
            }
            OreDiscovery discovery = discoveries.get(index);
            consumed += discovery.blocksMinedSincePrevious();
            if (!discovery.isHiddenDiscovery()) {
                continue;
            }
            // Effort accumulated since the previous hidden discovery (including any effort spent
            // on intervening non-hidden discoveries) is exactly this observation's waiting time.
            // For the first hidden discovery it is the effort since the window began.
            intervals.add(LikelihoodRatios.IntervalObservation.observed(consumed));
            hiddenSeen++;
            consumed = 0.0;
        }

        double remaining = Math.max(0.0, blocksMined - totalObservedEffort());
        if (hiddenSeen == 0) {
            intervals.add(LikelihoodRatios.IntervalObservation.censored(blocksMined));
        } else if (remaining > 0) {
            intervals.add(LikelihoodRatios.IntervalObservation.censored(remaining));
        }
        return intervals;
    }

    /**
     * Effort attributed to the interval series. The discovery at {@link #liveDiscoveryIndex()} is
     * omitted because its interval spans an unobservable gap; leaving its cost out of this total also
     * moves that cost into the trailing censored interval, which is the lenient direction.
     */
    private double totalObservedEffort() {
        double total = 0.0;
        for (int index = 0; index < discoveries.size(); index++) {
            if (index == liveDiscoveryIndex) {
                continue;
            }
            total += discoveries.get(index).blocksMinedSincePrevious();
        }
        return total;
    }

    /** Number of hidden-ore approach observations available for the targeting model. */
    public int measurableHiddenApproaches() {
        return (int) hiddenDiscoveries().stream().filter(d -> d.approach().hadData()).count();
    }
}
