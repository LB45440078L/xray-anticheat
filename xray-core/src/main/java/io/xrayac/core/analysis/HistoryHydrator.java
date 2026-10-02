package io.xrayac.core.analysis;

import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.repository.MiningEventRepository;
import io.xrayac.core.repository.OreDiscoveryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rebuilds a player's stored past and folds it into the live analysis window.
 *
 * <h2>Why this exists</h2>
 * A session's window is cumulative only within that session. Every restart, and every idle prune,
 * started the evidence base again from nothing, so a cheater's conduct was measured against a few
 * hours and a ban wave could never be justified by anything longer than the current login. Reading the
 * stored record back is what makes an assessment describe a player's whole time on the server.
 *
 * <h2>Threading</h2>
 * {@link #load} performs blocking database reads and <b>must never run on the Minecraft server
 * thread</b>. It is called from the analysis worker, which is also where {@link #merge} runs. The class
 * holds no mutable state, so the caller is free to cache its results; that is the caller's decision
 * because a cache belongs to whatever owns the scheduling policy.
 *
 * <h2>Honesty about the seam</h2>
 * Merging two sources creates one seam between the last stored discovery and the first of the current
 * session, and the length of that seam is unknown. It is declared rather than hidden, by recording the
 * index of the first live discovery on the merged window so the waiting-time model can drop the
 * interval spanning it. See {@link PlayerAnalysisWindow#hiddenDiscoveryIntervals()}.
 */
public final class HistoryHydrator {

    private final OreDiscoveryRepository discoveries;
    private final MiningEventRepository miningEvents;

    public HistoryHydrator(OreDiscoveryRepository discoveries, MiningEventRepository miningEvents) {
        this.discoveries = discoveries;
        this.miningEvents = miningEvents;
    }

    /**
     * Reads a player's stored history for one world.
     *
     * <p>Blocking. Returns {@link AnalysisHistory#empty()} rather than throwing when history is switched
     * off, so callers have one path to reason about; a repository failure propagates as
     * {@link io.xrayac.core.repository.PersistenceException} for the caller to handle, because silently
     * substituting an empty past would quietly resume the very reset this class exists to remove.
     *
     * <p>Both reads are newest-first at the database and reversed here, because the engine reads
     * trajectories and discoveries oldest-first.
     */
    public AnalysisHistory load(PlayerRef player, WorldId world, Instant now,
                                HistoryParameters parameters) {
        if (!parameters.enabled()) {
            return AnalysisHistory.empty();
        }
        Instant since = now.minus(parameters.lookback());

        List<OreDiscoveryRepository.StoredDiscovery> storedDiscoveries =
                discoveries.findRecent(player.id(), since, parameters.maxDiscoveries());
        List<Observation.Mining> storedMining =
                miningEvents.findRecent(player.id(), since, parameters.maxMiningEvents());

        // The queries are keyed on player alone, so they can return rows from other worlds. A window is
        // scoped to one world: ore distributions and exposure geometry are world-specific, and pooling
        // the overworld with the nether would corrupt both.
        List<OreDiscovery> forWorld = new ArrayList<>();
        List<Observation.Mining> miningForWorld = new ArrayList<>();
        for (OreDiscoveryRepository.StoredDiscovery stored : storedDiscoveries) {
            if (world.key().equals(stored.worldKey())) {
                forWorld.add(stored.discovery());
            }
        }
        for (Observation.Mining mining : storedMining) {
            if (world.equals(mining.world())) {
                miningForWorld.add(mining);
            }
        }

        forWorld.sort(Comparator.comparing(OreDiscovery::time));
        miningForWorld.sort(Comparator.comparing(Observation.Mining::timestamp));

        List<TrajectoryAnalysis.PathPoint> path = new ArrayList<>(miningForWorld.size());
        double historicalDistance = 0.0;
        for (Observation.Mining mining : miningForWorld) {
            path.add(TrajectoryAnalysis.PathPoint.of(mining.blockPos().center(), mining.timestamp()));
        }
        for (OreDiscovery discovery : forWorld) {
            // Only per-discovery distances are stored, so travel that produced no discovery is invisible
            // and this total can never exceed what the player really moved.
            historicalDistance += discovery.distanceTravelledSincePrevious();
        }

        Optional<Instant> earliest = earliestOf(forWorld, miningForWorld);
        // A read that returned its whole limit was cut off, so older data exists that was deliberately not
        // loaded. Reporting that keeps the history an honest floor rather than an implied complete past.
        boolean truncated = storedDiscoveries.size() >= parameters.maxDiscoveries()
                || storedMining.size() >= parameters.maxMiningEvents();

        return new AnalysisHistory(forWorld, miningForWorld.size(), historicalDistance, path,
                earliest, truncated);
    }

    /**
     * Folds stored history into a live window.
     *
     * <p>Discoveries are unioned and de-duplicated: a session's own discoveries are also written to the
     * database, so the same event can legitimately appear in both sources. Identity is the discovery's
     * world, block and instant, which is unique in practice because one vein yields one discovery.
     *
     * <p>Counts are summed, the paths concatenated in time order, and the window start moved back to the
     * earliest stored observation — which is how the horizon reaches a moderator, since the window
     * duration is quoted in the explanation.
     */
    public PlayerAnalysisWindow merge(PlayerAnalysisWindow live, AnalysisHistory history) {
        if (history.isEmpty()) {
            return live;
        }

        Set<String> seen = new HashSet<>();
        List<OreDiscovery> fromHistory = new ArrayList<>(history.discoveries().size());
        for (OreDiscovery discovery : history.discoveries()) {
            if (seen.add(identity(live.world(), discovery))) {
                fromHistory.add(discovery);
            }
        }
        List<OreDiscovery> fromLive = new ArrayList<>();
        for (OreDiscovery discovery : live.discoveries()) {
            if (seen.add(identity(live.world(), discovery))) {
                fromLive.add(discovery);
            }
        }

        // Tag by origin before sorting: a live discovery can in principle precede a stored one (clock
        // adjustment, or an overlap between the read and the session), and the boundary must mark the
        // first live discovery positionally rather than by counting, or it would point at the wrong entry.
        record Tagged(OreDiscovery discovery, boolean live) {
        }
        List<Tagged> tagged = new ArrayList<>(fromHistory.size() + fromLive.size());
        fromHistory.forEach(discovery -> tagged.add(new Tagged(discovery, false)));
        fromLive.forEach(discovery -> tagged.add(new Tagged(discovery, true)));
        tagged.sort(Comparator.comparing(entry -> entry.discovery().time()));

        List<OreDiscovery> merged = new ArrayList<>(tagged.size());
        int boundary = -1;
        for (int index = 0; index < tagged.size(); index++) {
            Tagged entry = tagged.get(index);
            merged.add(entry.discovery());
            if (boundary < 0 && entry.live()) {
                boundary = index;
            }
        }

        List<TrajectoryAnalysis.PathPoint> path =
                new ArrayList<>(history.miningPath().size() + live.pathPoints().size());
        path.addAll(history.miningPath());
        path.addAll(live.pathPoints());
        path.sort(Comparator.comparing(TrajectoryAnalysis.PathPoint::time));

        Instant start = history.earliestObservation()
                .filter(earliest -> earliest.isBefore(live.windowStart()))
                .orElse(live.windowStart());

        return new PlayerAnalysisWindow(live.player(), live.world(), start, live.windowEnd(),
                live.blocksMined() + history.blocksMined(),
                live.distanceTravelled() + history.distanceTravelled(),
                merged, path, boundary);
    }

    private static Optional<Instant> earliestOf(List<OreDiscovery> forWorld,
                                                List<Observation.Mining> miningForWorld) {
        Instant earliest = null;
        for (OreDiscovery discovery : forWorld) {
            if (earliest == null || discovery.time().isBefore(earliest)) {
                earliest = discovery.time();
            }
        }
        for (Observation.Mining mining : miningForWorld) {
            if (earliest == null || mining.timestamp().isBefore(earliest)) {
                earliest = mining.timestamp();
            }
        }
        return Optional.ofNullable(earliest);
    }

    /**
     * A discovery's identity across sources. The instant is taken at millisecond resolution because
     * that is the resolution the database stores.
     */
    private static String identity(WorldId world, OreDiscovery discovery) {
        return world.key() + ':' + discovery.discoveryBlock().x() + ','
                + discovery.discoveryBlock().y() + ',' + discovery.discoveryBlock().z()
                + '@' + discovery.time().toEpochMilli();
    }
}
