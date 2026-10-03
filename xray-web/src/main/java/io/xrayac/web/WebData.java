package io.xrayac.web;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.repository.BanWaveRepository;
import io.xrayac.core.repository.MiningEventRepository;
import io.xrayac.core.repository.ModeratorActionRepository;
import io.xrayac.core.repository.OreDiscoveryRepository;
import io.xrayac.core.repository.PlayerRepository;
import io.xrayac.core.repository.SuspicionRepository;
import io.xrayac.core.repository.WorldModificationRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the panel: a thin facade over the repository interfaces.
 *
 * <p>Every method here is a read, except {@link #recordAction}, which writes an audit entry. Nothing in
 * this class writes player data, changes a verdict or edits a discovery. Moderation actions go through
 * {@link ModerationActions} instead, so the panel cannot silently alter the evidence it is supposed to
 * be reviewing.
 *
 * <h2>Bounded reads</h2>
 * Every listing method takes a limit, and the panel passes its configured page size. The repository
 * interfaces offer no unbounded query, and this facade does not invent one: an admin console that
 * issues a full table scan the first time a busy server is opened is a denial-of-service tool pointed
 * at its own server.
 *
 * <p>That means the counts shown are <em>windowed</em> counts, and they are labelled as such in the
 * interface rather than presented as table totals. Where a true total exists (world modifications) it is
 * used.
 */
public final class WebData {

    /**
     * The repository set, bundled so the constructor does not take seven positional arguments of the
     * same shape - a mistake that would compile and fail at runtime.
     */
    public record Repositories(
            PlayerRepository players,
            SuspicionRepository suspicion,
            OreDiscoveryRepository discoveries,
            ModeratorActionRepository actions,
            BanWaveRepository banWaves,
            MiningEventRepository mining,
            WorldModificationRepository world) {
    }

    /** A player row: identity, recency, and the latest assessment when there is one. */
    public record PlayerRow(
            UUID id,
            String name,
            Instant firstSeen,
            Instant lastSeen,
            SuspicionSnapshot latest,
            String latestWorldKey) {

        public double score() {
            return latest == null ? Double.NaN : latest.suspicionScore();
        }

        public String strength() {
            return latest == null ? null : latest.evidenceStrength().name();
        }

        public int sampleSize() {
            return latest == null ? 0 : latest.sampleSize();
        }
    }

    /** Headline figures for the dashboard. */
    public record Overview(
            int trackedPlayers,
            int trackedLimit,
            int candidates,
            long worldRemovals,
            int assessmentsForTopPlayer,
            Instant lastWaveAt,
            int recentWaves,
            String dialect,
            int schemaVersion) {
    }

    private final Repositories repositories;
    private final String dialect;
    private final int schemaVersion;
    private final UUID panelActorId;

    public WebData(Repositories repositories, String dialect, int schemaVersion, UUID panelActorId) {
        this.repositories = repositories;
        this.dialect = dialect;
        this.schemaVersion = schemaVersion;
        this.panelActorId = panelActorId;
    }

    /** How many players the overview looks at when reporting "tracked". */
    private static final int OVERVIEW_PLAYER_WINDOW = 500;

    public Overview overview() {
        List<PlayerRepository.StoredPlayer> recent =
                repositories.players().mostRecentlySeen(OVERVIEW_PLAYER_WINDOW);
        List<BanWaveCandidate> candidates = repositories.banWaves().candidates();
        int assessments = recent.isEmpty()
                ? 0
                : repositories.suspicion().history(recent.get(0).player().id(), 200).size();
        return new Overview(
                recent.size(),
                OVERVIEW_PLAYER_WINDOW,
                candidates.size(),
                repositories.world().count(),
                assessments,
                repositories.banWaves().lastExecutedWaveAt().orElse(null),
                repositories.banWaves().recentWaves(10).size(),
                dialect,
                schemaVersion);
    }

    /** Players by recency, each with their latest assessment. */
    public List<PlayerRow> players(int limit) {
        List<PlayerRow> rows = new ArrayList<>();
        for (PlayerRepository.StoredPlayer stored : repositories.players().mostRecentlySeen(limit)) {
            rows.add(rowFor(stored.player(), stored.firstSeen(), stored.lastSeen()));
        }
        return rows;
    }

    private PlayerRow rowFor(PlayerRef player, Instant firstSeen, Instant lastSeen) {
        Optional<SuspicionSnapshot> latest = repositories.suspicion().latest(player.id());
        return new PlayerRow(
                player.id(),
                player.name(),
                firstSeen,
                lastSeen,
                latest.orElse(null),
                latest.map(s -> s.world().key()).orElse(null));
    }

    /** A single player, or empty when the id is unknown. */
    public Optional<PlayerRow> player(UUID id) {
        return repositories.players().find(id).map(stored ->
                rowFor(stored.player(), stored.firstSeen(), stored.lastSeen()));
    }

    /**
     * A player row for an id the player table does not know.
     *
     * <p>Ban-wave candidates can outlive a row in the player table (retention runs independently), and
     * a candidate whose page 404s would look like lost data. The candidate carries the name, so the row
     * is rebuilt from it with the timestamps marked absent.
     */
    public Optional<PlayerRow> playerOrCandidate(UUID id) {
        Optional<PlayerRow> stored = player(id);
        if (stored.isPresent()) {
            return stored;
        }
        return repositories.banWaves().candidates().stream()
                .filter(c -> c.player().id().equals(id))
                .findFirst()
                .map(c -> new PlayerRow(
                        c.player().id(), c.player().name(), null, c.lastDetected(), null,
                        c.world().key()));
    }

    /** Assessment history, newest first. */
    public List<SuspicionSnapshot> history(UUID id, int limit) {
        return repositories.suspicion().history(id, limit);
    }

    /** Ore discoveries inside a window. */
    public List<OreDiscoveryRepository.StoredDiscovery> discoveries(UUID id, Duration window, int limit) {
        return repositories.discoveries().findRecent(id, Instant.now().minus(window), limit);
    }

    /** Mining events inside a window. */
    public List<Observation.Mining> mining(UUID id, Duration window, int limit) {
        return repositories.mining().findRecent(id, Instant.now().minus(window), limit);
    }

    /** Moderation actions recorded against a player, newest first. */
    public List<ModeratorActionRepository.StoredAction> actionsFor(UUID id, int limit) {
        return repositories.actions().forPlayer(id, limit);
    }

    /** The audit trail across all players, newest first. */
    public List<ModeratorActionRepository.StoredAction> recentActions(int limit) {
        return repositories.actions().recent(limit);
    }

    /**
     * Removes a player from the ban-wave candidate list.
     *
     * <p>This deletes the candidate row only. The assessments and discoveries that produced it are
     * untouched, so dismissing a candidate is a decision about what is <em>queued</em>, never a
     * rewrite of the evidence - which is what makes it safe to expose in a console.
     */
    public void dismissCandidate(UUID playerId) {
        repositories.banWaves().deleteCandidate(playerId);
    }

    /** Current ban-wave candidates. */
    public List<BanWaveCandidate> candidates() {
        return repositories.banWaves().candidates();
    }

    /** Past ban waves, newest first. */
    public List<BanWaveRepository.WaveRecord> waves(int limit) {
        return repositories.banWaves().recentWaves(limit);
    }

    /**
     * Records an audit entry for something done through the panel.
     *
     * <p>Attributed to {@code panelActorId}, which is not a real player: the operator is authenticated as
     * a console user, not as a Minecraft account. Reusing a player UUID here would corrupt the audit
     * trail by attributing staff actions to a player.
     */
    public void recordAction(UUID playerId, String action, String note) {
        repositories.actions().record(playerId, panelActorId, action, note, Instant.now());
    }

    /** The actor id the panel writes into the audit trail. */
    public UUID panelActorId() {
        return panelActorId;
    }

    /**
     * A per-ore tally of a player's discoveries, for the breakdown on the detail page.
     *
     * @return ore id to count, ordered by count descending
     */
    public List<java.util.Map.Entry<String, Integer>> oreTally(UUID id, Duration window, int limit) {
        java.util.Map<String, Integer> tally = new java.util.HashMap<>();
        for (OreDiscoveryRepository.StoredDiscovery stored : discoveries(id, window, limit)) {
            tally.merge(stored.discovery().oreId(), 1, Integer::sum);
        }
        return tally.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .toList();
    }

    /** How much of a discovery's vein was hidden, as a ratio, for the exposure summary. */
    public double hiddenRatio(OreDiscovery discovery) {
        int total = discovery.hiddenVeinBlocks() + discovery.exposedVeinBlocks();
        return total == 0 ? Double.NaN : (double) discovery.hiddenVeinBlocks() / total;
    }
}
