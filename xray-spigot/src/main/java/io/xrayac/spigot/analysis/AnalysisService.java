package io.xrayac.spigot.analysis;

import io.xrayac.core.analysis.AnalysisHistory;
import io.xrayac.core.analysis.HistoryHydrator;
import io.xrayac.core.analysis.HistoryParameters;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.decision.ActionType;
import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.decision.BanWavePlan;
import io.xrayac.core.decision.BanWavePlanner;
import io.xrayac.core.decision.DecisionEngine;
import io.xrayac.core.decision.DecisionOutcome;
import io.xrayac.core.evidence.EvidenceEngine;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.repository.PersistenceException;
import io.xrayac.spigot.alert.AlertService;
import io.xrayac.spigot.config.PluginSettings;
import io.xrayac.spigot.persistence.PersistenceBundle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the analysis pipeline for a frozen window and acts on the result.
 *
 * <h2>The thread boundary, concretely</h2>
 * {@link #submit(PlayerAnalysisWindow)} is called on the Minecraft server thread and does exactly one
 * thing: hand an immutable window to a worker. Everything that follows — evaluation, persistence,
 * candidate bookkeeping — happens on the worker. Whenever the result requires touching the server
 * (sending an alert, kicking or banning a player), that step is scheduled back onto the server thread
 * through {@link #onMainThread}. No method in this class calls a repository or the evaluator from the
 * server thread, and none calls the Bukkit API from a worker.
 *
 * <h2>Cumulative, not sliding, evidence — and one honest caveat</h2>
 * A session's window is cumulative: each pass re-evaluates everything the session has recorded, so
 * evidence accumulates rather than being recomputed on a short recent slice. That is the correct
 * reading of a Poisson rate test, and it is what lets a player who has been mining for an hour be
 * judged on an hour of evidence.
 *
 * <p>The caveat: {@code blocksMined} is a running scalar, whereas the discovery buffer is bounded and
 * discards its oldest entries. On a session long enough to overflow the buffer, the buried-discovery
 * count stops growing while the mined-block count does not, so the measured rate drifts downward and
 * the assessment becomes progressively <i>more</i> lenient. The drift is in the safe direction and is
 * bounded by the buffer size, but it is a real limitation of the cumulative design and is recorded
 * here rather than left for someone to rediscover.
 */
public final class AnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisService.class);

    private final Plugin plugin;
    private final ExecutorService workers;
    private final EvidenceEngine engine;
    private final Supplier<PluginSettings> settings;
    private final AlertService alerts;
    private final PersistenceBundle persistence;
    private final java.util.function.BiConsumer<SuspicionSnapshot, DecisionOutcome> enforcement;

    /**
     * The runtime debug state, flipped by {@code /xray debug}.
     *
     * <p>Read on each assessment rather than captured, so the command takes effect immediately
     * instead of only after a restart. A configuration reload resets it to the file's value.
     */
    private final java.util.function.BooleanSupplier runtimeDebug;

    private final BanWavePlanner wavePlanner = new BanWavePlanner();
    private final List<BanWaveCandidate> candidates = new CopyOnWriteArrayList<>();

    /**
     * The most recent assessment for each player.
     *
     * <p>Exists so that the moderator interface can render without a database read. The GUI runs on
     * the server thread, where a blocking query is forbidden, so it cannot fetch a player's stored
     * snapshot on demand; this cache is what it reads instead. A {@link java.util.concurrent.ConcurrentHashMap}
     * is used because the writers are analysis workers and the readers are the server thread.
     */
    private final java.util.Map<UUID, SuspicionSnapshot> latestAssessments =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicLong assessments = new AtomicLong();
    private final AtomicLong alertsRaised = new AtomicLong();
    private final AtomicLong lastWaveAtEpochMillis = new AtomicLong(-1L);

    /**
     * Stored histories, keyed by player and world, re-read only when they have aged past their refresh
     * interval.
     *
     * <p>Hydration is a pair of database reads, so doing it on every assessment would put a query per
     * player per pass on the database for no gain: a player's ninety-day past changes by seconds'
     * worth between passes. Written by analysis workers, so concurrent. Bounded by
     * {@link #HISTORY_CACHE_LIMIT} with opportunistic expiry, because a server sees many players who
     * never come back.
     */
    private final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();

    /** Above this many cached histories, expired entries are swept on the next hydration. */
    private static final int HISTORY_CACHE_LIMIT = 512;

    public AnalysisService(Plugin plugin,
                           ExecutorService workers,
                           EvidenceEngine engine,
                           Supplier<PluginSettings> settings,
                           AlertService alerts,
                           PersistenceBundle persistence,
                           java.util.function.BiConsumer<SuspicionSnapshot, DecisionOutcome> enforcement,
                           java.util.function.BooleanSupplier runtimeDebug) {
        this.plugin = plugin;
        this.workers = workers;
        this.engine = engine;
        this.settings = settings;
        this.alerts = alerts;
        this.persistence = persistence;
        this.enforcement = enforcement;
        this.runtimeDebug = runtimeDebug;
    }

    /**
     * Submits a frozen window for analysis. Called on the server thread; returns immediately.
     */
    public void submit(PlayerAnalysisWindow window) {
        workers.execute(() -> analyse(window));
    }

    /**
     * The pipeline, running on a worker thread.
     *
     * <p>Failures are contained per player: one player's evaluation throwing must not stop the
     * analysis of everyone else, so the whole body is guarded and the error is logged with the
     * player's identity.
     */
    private void analyse(PlayerAnalysisWindow window) {
        try {
            PluginSettings current = settings.get();
            Instant now = Instant.now();

            // Fold in everything already stored about this player before evaluating. This is what makes an
            // assessment describe their whole recorded time rather than the current login, and it is why a
            // restart no longer resets the evidence base. The read blocks, which is precisely why it happens
            // here on the worker and never in the collector on the server thread.
            PlayerAnalysisWindow effective =
                    historyHydrator().merge(window, historyFor(window, current, now));

            SuspicionSnapshot snapshot = engine.evaluate(effective,
                    current.oreProfileRegistry(), current.evidenceParameters(), now);

            DecisionOutcome outcome = new DecisionEngine(current.decisionPolicy()).decide(snapshot, now);

            if (current.debug().enabled() || current.debug().logAnalysis() || runtimeDebug.getAsBoolean()) {
                LOGGER.info("Assessment for {}: {}\n{}", window.player().display(),
                        outcome.action(), snapshot.explain());
            }

            assessments.incrementAndGet();
            latestAssessments.put(window.player().id(), snapshot);

            if (!current.suspicionEnabled()) {
                // Suspicion is switched off, so no alert and no action is taken — but the assessment
                // is still stored. An administrator who disables suspicion is saying "do not act on
                // this", not "delete the history"; keeping the record continuous means re-enabling it
                // later does not leave a gap in the audit trail.
                persist(snapshot);
                return;
            }

            persist(snapshot);
            handleCandidate(snapshot, outcome, current);

            if (outcome.action() != ActionType.NONE) {
                onMainThread(() -> {
                    if (alerts.alert(snapshot, outcome)) {
                        alertsRaised.incrementAndGet();
                    }
                });
            }

            if (outcome.action() == ActionType.KICK || outcome.action() == ActionType.BAN) {
                // The outcome is passed through, not just the snapshot: the handler must know whether
                // the evidence justified a kick or a ban, because carrying out the wrong one either
                // under-enforces or removes a player who should merely have been disconnected.
                onMainThread(() -> enforcement.accept(snapshot, outcome));
            }
        } catch (RuntimeException e) {
            // One player's failure must not deny everyone else an assessment.
            LOGGER.error("Analysis failed for {}; other players are unaffected",
                    window.player().id(), e);
        }
    }

    private HistoryHydrator historyHydrator() {
        return new HistoryHydrator(persistence.oreDiscoveries(), persistence.miningEvents());
    }

    /**
     * The stored past for a player, re-read only once the cached copy has aged out.
     *
     * <p>Returns an empty history — session-only analysis — when the feature is switched off, when the
     * database is not available, or when a read fails. The failure cases are logged rather than passed
     * over, because falling back to the session alone is exactly the reset this feature exists to remove:
     * an operator who saw only quiet successes would never learn that history had stopped being read.
     */
    private AnalysisHistory historyFor(PlayerAnalysisWindow window, PluginSettings current,
                                       Instant now) {
        PluginSettings.History configured = current.history();
        if (!configured.enabled() || !persistence.isAvailable()) {
            return AnalysisHistory.empty();
        }

        HistoryParameters parameters = configured.toParameters();
        String key = window.player().id() + "#" + window.world().key();
        CachedHistory cached = historyCache.get(key);
        if (cached != null
                && Duration.between(cached.loadedAt(), now).compareTo(parameters.refreshInterval()) < 0) {
            return cached.history();
        }

        AnalysisHistory loaded;
        try {
            loaded = historyHydrator().load(window.player(), window.world(), now, parameters);
        } catch (PersistenceException e) {
            LOGGER.warn("Could not read stored history for {}; this assessment covers the current session "
                    + "only, so it is narrower than configured: {}",
                    window.player().display(), e.getMessage());
            return AnalysisHistory.empty();
        }

        historyCache.put(key, new CachedHistory(loaded, now));
        pruneHistoryCache(now, parameters);
        return loaded;
    }

    /** A hydrated history and the instant it was read. */
    private record CachedHistory(AnalysisHistory history, Instant loadedAt) {
    }

    /**
     * Sweeps cached histories that have aged well beyond their refresh interval.
     *
     * <p>Only runs once the cache is large, so the cost is paid rarely, and it bounds memory on a server
     * that sees thousands of distinct players over its lifetime.
     */
    private void pruneHistoryCache(Instant now, HistoryParameters parameters) {
        if (historyCache.size() <= HISTORY_CACHE_LIMIT) {
            return;
        }
        Duration idle = parameters.refreshInterval().multipliedBy(4);
        historyCache.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().loadedAt(), now).compareTo(idle) > 0);
    }

    private void persist(SuspicionSnapshot snapshot) {
        if (!persistence.isAvailable()) {
            return;
        }
        try {
            persistence.suspicion().save(snapshot);
        } catch (PersistenceException e) {
            // A failed write flips the store to unavailable so that the rest of the plugin stops
            // hammering a broken database; recovery is attempted on a timer.
            persistence.markUnavailable();
            LOGGER.error("Could not store the assessment for {}; the store is now marked unavailable",
                    snapshot.player().id(), e);
        }
    }

    /**
     * Keeps the ban-wave candidate list current.
     *
     * <p>Candidates are only tracked in {@link io.xrayac.core.decision.EnforcementMode#BAN_WAVE}; in
     * any other mode the concept does not apply and the list stays empty.
     */
    private void handleCandidate(SuspicionSnapshot snapshot, DecisionOutcome outcome,
                                 PluginSettings current) {
        if (outcome.action() != ActionType.CANDIDATE) {
            return;
        }
        List<BanWaveCandidate> updated = wavePlanner.consider(candidates, snapshot,
                current.decisionPolicy(), Instant.now());
        candidates.clear();
        candidates.addAll(updated);

        if (persistence.isAvailable()) {
            try {
                // The newly-considered candidate is the last one in the list only when the player was
                // appended; merging may instead have updated an existing entry. Finding it by id is
                // the only correct way to persist exactly the row that changed.
                updated.stream()
                        .filter(candidate -> candidate.player().id().equals(snapshot.player().id()))
                        .findFirst()
                        .ifPresent(candidate -> persistence.banWaves().upsertCandidate(candidate));
            } catch (PersistenceException e) {
                LOGGER.error("Could not store a ban-wave candidate for {}", snapshot.player().id(), e);
            }
        }
    }

    /**
     * Evaluates whether a ban wave should run now.
     *
     * <p>Returns a plan, or a reason not to run — a refusal always states why, because "no wave" is
     * unhelpful to an administrator who is expecting one.
     */
    public BanWavePlanner.PlanDecision planWave(Instant now) {
        PluginSettings current = settings.get();
        Instant lastWave = lastWaveAtEpochMillis.get() < 0
                ? null : Instant.ofEpochMilli(lastWaveAtEpochMillis.get());
        return wavePlanner.plan(candidates, now, lastWave, current.decisionPolicy());
    }

    /** Records that a wave was executed, so the inter-wave interval starts counting. */
    public void recordWaveExecuted(BanWavePlan plan, Instant executedAt) {
        lastWaveAtEpochMillis.set(executedAt.toEpochMilli());
        for (BanWaveCandidate candidate : plan.candidates()) {
            candidates.removeIf(existing -> existing.player().id().equals(candidate.player().id()));
        }
        if (persistence.isAvailable()) {
            try {
                persistence.banWaves().recordWave(plan, executedAt);
                for (BanWaveCandidate candidate : plan.candidates()) {
                    persistence.banWaves().deleteCandidate(candidate.player().id());
                }
            } catch (PersistenceException e) {
                LOGGER.error("Could not record the ban wave", e);
            }
        }
    }

    /** Loads previously stored candidates, so a restart does not forget them. */
    public void loadStoredCandidates() {
        if (!persistence.isAvailable()) {
            return;
        }
        try {
            List<BanWaveCandidate> stored = persistence.banWaves().candidates();
            candidates.clear();
            candidates.addAll(stored);
            persistence.banWaves().lastExecutedWaveAt().ifPresent(
                    at -> lastWaveAtEpochMillis.set(at.toEpochMilli()));
        } catch (PersistenceException e) {
            LOGGER.error("Could not load stored ban-wave candidates", e);
        }
    }

    /** The current candidate list, for commands and the GUI. */
    public List<BanWaveCandidate> candidates() {
        return new ArrayList<>(candidates);
    }

    /**
     * The most recent in-memory assessment for a player, if they have been assessed.
     *
     * <p>Returns what the last pass concluded, not what is stored. It is the right source for a live
     * interface and the wrong source for an audit; the durable record lives in the database.
     */
    public java.util.Optional<SuspicionSnapshot> latestAssessment(UUID playerId) {
        return java.util.Optional.ofNullable(latestAssessments.get(playerId));
    }

    public long assessmentCount() {
        return assessments.get();
    }

    public long alertCount() {
        return alertsRaised.get();
    }

    public Instant lastWaveAt() {
        long millis = lastWaveAtEpochMillis.get();
        return millis < 0 ? null : Instant.ofEpochMilli(millis);
    }

    /**
     * Runs a task on the Minecraft server thread.
     *
     * <p>Every interaction with the server or its players must come back through here. The scheduler
     * call is safe from a worker thread; the task itself is not, which is why the worker never
     * performs the action directly.
     */
    private void onMainThread(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
