package io.xrayac.paper;

import io.xrayac.core.config.OreProfile;
import io.xrayac.core.decision.EnforcementMode;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.evidence.EvidenceEngine;
import io.xrayac.core.evidence.component.ExposureMixComponent;
import io.xrayac.core.evidence.component.HiddenDiscoveryRateComponent;
import io.xrayac.core.evidence.component.InterDiscoveryWaitingComponent;
import io.xrayac.core.evidence.component.OreTargetingComponent;
import io.xrayac.core.evidence.component.TunnelGeometryComponent;
import io.xrayac.core.repository.PersistenceException;
import io.xrayac.paper.adapter.BukkitWorldView;
import io.xrayac.paper.adapter.ExcavationLedger;
import io.xrayac.paper.adapter.MaterialClassifier;
import io.xrayac.paper.alert.AlertService;
import io.xrayac.paper.analysis.AnalysisService;
import io.xrayac.paper.command.XRayCommand;
import io.xrayac.paper.config.ConfigLoader;
import io.xrayac.paper.config.PluginSettings;
import io.xrayac.paper.enforcement.EnforcementService;
import io.xrayac.paper.gui.GuiManager;
import io.xrayac.paper.listener.ObservationListener;
import io.xrayac.paper.message.ConsoleReporter;
import io.xrayac.paper.message.MessageService;
import io.xrayac.paper.persistence.PendingPersistence;
import io.xrayac.paper.persistence.PersistenceBundle;
import io.xrayac.paper.session.SessionRegistry;
import io.xrayac.persistence.DatabaseConfig;
import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin entry point: construction, scheduling and shutdown.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 * This is composition only. It builds the object graph, schedules the background work and tears it
 * down; it contains no analysis, no decision logic and no persistence logic. Every one of those lives
 * behind an interface in a lower module, which is why the plugin layer stays thin enough to read in one
 * sitting and why the mathematics can be tested without a server.
 *
 * <h2>Wiring is constructor injection, not a framework</h2>
 * Dependencies are passed explicitly. A dependency-injection framework would add startup cost, a
 * reflective failure mode and a configuration surface for a graph of about a dozen objects, while
 * obscuring the one thing a reader most needs to see here: which component owns which thread.
 *
 * <h2>Thread ownership, restated because it is the point</h2>
 * The server thread collects observations and renders the interface. Workers evaluate evidence, write
 * to the database and run retention. Nothing that blocks runs on the server thread, and nothing that
 * touches the Bukkit API runs on a worker. The only bridge back is {@link #runSync(Runnable)}.
 */
public final class XRayAntiCheatPlugin extends JavaPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(XRayAntiCheatPlugin.class);

    /** Throttle applied to repeated alerts about the same player. */
    private static final Duration ALERT_THROTTLE = Duration.ofMinutes(5);

    private volatile PluginSettings settings;
    private ExecutorService workers;

    private MessageService messages;
    private ConsoleReporter console;
    private FileConfiguration guiConfiguration;

    private PersistenceBundle persistence;
    private ExcavationLedger ledger;
    private MaterialClassifier classifier;
    private BukkitWorldView worldView;
    private SessionRegistry sessions;
    private PendingPersistence pending;
    private EvidenceEngine engine;
    private AlertService alerts;
    private EnforcementService enforcement;
    private AnalysisService analysis;
    private GuiManager gui;

    private volatile boolean debugEnabled;
    private int schemaVersion;
    private boolean migrationsOnStartup = true;
    private boolean continueWithoutDatabase = true;
    private int databaseRetrySeconds = 30;

    @Override
    public void onEnable() {
        // config.yml must be written to disk before it is read. getConfig() only *reads* the file and
        // never creates it, and nothing else here did either — so the file was simply absent and every
        // setting silently fell back to its built-in default. saveDefaultConfig() writes the jar's copy
        // when none exists; reloadConfig() then reads what is on disk.
        saveDefaultConfig();
        reloadConfig();
        warnIfConfigIsNewer("config.yml", getConfig());

        saveDefaultConfigResource("database.yml");
        saveDefaultConfigResource("messages.yml");
        saveDefaultConfigResource("gui.yml");

        ConfigLoader loader = new ConfigLoader();
        try {
            ConfigLoader.LoadResult result = loader.load(getConfig());
            this.settings = result.settings();
            for (String warning : result.warnings()) {
                LOGGER.warn("Configuration: {}", warning);
            }
        } catch (ConfigLoader.FatalConfigurationException e) {
            // The only fatal failures are those that would leave the engine with nothing to analyse.
            // Running anyway would let an administrator believe detection was active when it could
            // never fire, which is worse than refusing to start.
            LOGGER.error("The plugin cannot start: {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadMessageAndGuiConfiguration();
        // Wraps the same MessageService instance every other component holds, so a later reload reaches the
        // console reporting too, and created here because the banner it prints comes from messages.yml.
        this.console = new ConsoleReporter(messages);
        this.debugEnabled = settings.debug().enabled();

        this.workers = createWorkerPool();

        if (!connectPersistence(loader)) {
            return;
        }
        if (!isEnabled()) {
            return;
        }

        this.ledger = new ExcavationLedger(settings.performance().ledgerMaxEntries());
        this.classifier = new MaterialClassifier();
        this.worldView = new BukkitWorldView(ledger, classifier);
        this.sessions = new SessionRegistry(
                settings.tracking().pathBufferSize(),
                settings.tracking().miningBufferSize(),
                settings.tracking().discoveryHistorySize());
        this.pending = new PendingPersistence(1_000_000);

        this.engine = new EvidenceEngine(List.of(
                new HiddenDiscoveryRateComponent(),
                new InterDiscoveryWaitingComponent(),
                new OreTargetingComponent(),
                new ExposureMixComponent(),
                new TunnelGeometryComponent()));

        this.alerts = new AlertService(this, messages, ALERT_THROTTLE);
        this.enforcement = new EnforcementService(this, messages, persistence, this::runAsync);
        this.analysis = new AnalysisService(this, workers, engine, this::settings, alerts, persistence,
                enforcement, () -> debugEnabled);

        if (settings.suspicionEnabled()) {
            analysis.loadStoredCandidates();
        }

        // The interface reads the configuration through a supplier, so a reload changes the menus
        // without the plugin having to rebuild the manager and lose anything it holds.
        this.gui = new GuiManager(() -> guiConfiguration, messages, analysis, sessions, enforcement,
                alerts);

        registerListener();
        registerCommand();
        scheduleTasks();

        printStartupReport();
    }

    /**
     * Prints the banner and the startup summary to the server console.
     *
     * <p>Replaces a single {@code LOGGER.info} line. The console is addressed as a {@link
     * org.bukkit.command.CommandSender} rather than through the logger so the server translates the
     * colour codes for its terminal — see {@link ConsoleReporter}.
     */
    private void printStartupReport() {
        Map<String, String> placeholders = consolePlaceholders();
        console.banner(placeholders);
        console.lines("lifecycle.startup", placeholders);
        if (settings.analysisEnabled()) {
            console.line("lifecycle.ready", placeholders);
        } else {
            // Saying "enabled" while analysis is switched off would let an administrator believe players
            // were being observed when nothing was being collected at all.
            console.line("lifecycle.disabled-by-config", placeholders);
        }
    }

    /**
     * The placeholder values the console reporting uses.
     *
     * <p>Every key here is referenced by {@code messages.yml}; a placeholder this method does not supply
     * would be printed literally to the console, which is how a startup line turns into a puzzle.
     */
    private Map<String, String> consolePlaceholders() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("version", getPluginMeta().getVersion());
        placeholders.put("dialect", persistence != null && persistence.isAvailable()
                ? persistence.dialect().name() : "unavailable");
        placeholders.put("schema-version", String.valueOf(schemaVersion));
        placeholders.put("mode", settings.decisionPolicy().mode().name());
        placeholders.put("ores", settings.enabledOres().stream()
                .map(OreProfile::displayName)
                .collect(Collectors.joining(", ")));
        placeholders.put("ore-count", String.valueOf(settings.enabledOres().size()));
        placeholders.put("threads", settings.performance().analysisThreads() + " "
                + (settings.performance().useVirtualThreads() ? "virtual" : "platform"));
        placeholders.put("lookback", String.valueOf(settings.history().lookbackDays()));
        return placeholders;
    }

    @Override
    public void onDisable() {
        if (workers != null) {
            workers.shutdown();
            try {
                // A brief grace period lets in-flight analyses finish. Blocking here is acceptable
                // because the server is stopping, and leaving work unfinished would discard evidence.
                if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workers.shutdownNow();
            }
        }

        if (persistence != null) {
            // The final flush runs inline. At shutdown the tick loop no longer exists to protect, and
            // handing the write to a pool that is being torn down is how the last minutes of a session
            // get lost.
            flushPendingObservations();
            flushExcavationLedger();
            persistence.close();
        }
        if (console != null && settings != null) {
            console.line("lifecycle.shutdown", consolePlaceholders());
        } else {
            // A disable during a failed enable has no message file or settings to quote, but the console
            // still has to be told the plugin stopped.
            LOGGER.info("XRay AntiCheat disabled");
        }
    }

    // -------------------------------------------------------------------------------------
    // Lifecycle helpers
    // -------------------------------------------------------------------------------------

    private void saveDefaultConfigResource(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    private void loadMessageAndGuiConfiguration() {
        FileConfiguration loadedMessages = loadYaml("messages.yml");
        if (messages == null) {
            this.messages = new MessageService(loadedMessages);
        } else {
            // Reloaded in place. AlertService, EnforcementService, the command layer and the interface
            // already hold this instance; replacing it would leave every one of them quoting the
            // previous file, so a corrected message would apply everywhere except where it mattered.
            messages.reload(loadedMessages);
        }
        this.guiConfiguration = loadYaml("gui.yml");
    }

    private FileConfiguration loadYaml(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        FileConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        warnIfConfigIsNewer(name, configuration);
        return configuration;
    }

    /**
     * The configuration layout version this build understands.
     *
     * <p>Declared in each shipped file as {@code config-version}.
     */
    private static final int CONFIG_VERSION = 1;

    /**
     * Warns when a configuration file was written by a newer release of the plugin.
     *
     * <p>Without this, {@code config-version} would be decoration: an administrator upgrading the plugin
     * while keeping the old files, or downgrading, would get no signal at all. A file from a newer
     * version may carry settings this build does not know about and would ignore silently, which is
     * precisely the "my option does nothing" complaint this check exists to pre-empt. A missing key is
     * treated as current rather than warned about, because hand-written or older files legitimately lack
     * it.
     */
    private void warnIfConfigIsNewer(String fileName, FileConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        int declared = configuration.getInt("config-version", CONFIG_VERSION);
        if (declared > CONFIG_VERSION) {
            LOGGER.warn("{} declares config-version {} but this build understands {}; settings added by "
                            + "the newer version will be ignored until the plugin is updated",
                    fileName, declared, CONFIG_VERSION);
        }
    }

    private ExecutorService createWorkerPool() {
        if (settings.performance().useVirtualThreads()) {
            // The work is dominated by blocking JDBC calls, which is exactly what virtual threads are
            // for: one virtual thread per task costs almost nothing and lets a slow database round
            // trip overlap with hundreds of others instead of occupying one of a handful of platform
            // threads.
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        return Executors.newFixedThreadPool(settings.performance().analysisThreads(), runnable -> {
            Thread thread = new Thread(runnable, "xray-anticheat-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Connects to storage.
     *
     * @return true when the plugin may continue; false when it has disabled itself
     */
    private boolean connectPersistence(ConfigLoader loader) {
        FileConfiguration databaseConfiguration = loadYaml("database.yml");
        this.migrationsOnStartup = loader.runMigrationsOnStartup(databaseConfiguration);
        this.continueWithoutDatabase = loader.continueWithoutDatabase(databaseConfiguration);
        this.databaseRetrySeconds = loader.databaseRetryIntervalSeconds(databaseConfiguration);

        DatabaseConfig databaseConfig = loader.loadDatabase(databaseConfiguration);
        try {
            this.persistence = PersistenceBundle.connect(databaseConfig,
                    settings.performance().persistenceBatchSize(), migrationsOnStartup);
            this.schemaVersion = persistence.schemaVersion();
            if (persistence.migrationsApplied() > 0) {
                // Reported rather than left to the log: a schema change is the one startup event an
                // administrator needs to know about before anything else happens to their data.
                console.line("lifecycle.schema-migrated",
                        Map.of("count", String.valueOf(persistence.migrationsApplied())));
            }
            return true;
        } catch (SQLException e) {
            if (!continueWithoutDatabase) {
                LOGGER.error("The database is unavailable and "
                        + "storage.fail-safe.continue-without-database is false, so the plugin will not "
                        + "enable", e);
                getServer().getPluginManager().disablePlugin(this);
                return false;
            }
            // The stack stays at debug level: the console gets the configured line, and an operator who
            // needs the detail turns on debug logging instead of being handed a trace on every start.
            LOGGER.debug("Storage connection failure detail", e);
            console.line("lifecycle.database-unavailable", Map.of("error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            this.persistence = PersistenceBundle.unavailable(databaseConfig,
                    settings.performance().persistenceBatchSize());
            return true;
        }
    }

    private void registerListener() {
        ObservationListener listener = new ObservationListener(
                this::settings, sessions, ledger, worldView, classifier, pending, analysis,
                this::recordPlayerSeen);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(gui, this);
    }

    /** Records a player's existence so reports can resolve a name to a UUID. */
    private void recordPlayerSeen(PlayerRef player) {
        if (!persistence.isAvailable()) {
            return;
        }
        Instant now = Instant.now();
        runAsync(() -> {
            try {
                persistence.players().upsert(player, now);
            } catch (PersistenceException e) {
                LOGGER.warn("Could not record player {}: {}", player.id(), e.getMessage());
            }
        });
    }

    private void registerCommand() {
        PluginCommand command = getCommand("xray");
        if (command == null) {
            LOGGER.error("The 'xray' command is missing from plugin.yml; the plugin will not be usable");
            return;
        }
        XRayCommand executor = new XRayCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    // -------------------------------------------------------------------------------------
    // Scheduled work
    // -------------------------------------------------------------------------------------

    private void scheduleTasks() {
        long analysisTicks = settings.tracking().analysisIntervalMinutes() * 60L * 20L;
        long flushTicks = settings.performance().flushIntervalSeconds() * 20L;

        // Periodic analysis reads the sessions, which the server thread owns, so it runs on the server
        // thread — but it only builds immutable snapshots and hands them to workers.
        getServer().getScheduler().runTaskTimer(this, this::analyseActiveSessions,
                analysisTicks, analysisTicks);

        // Flushing performs database writes, so it must not run on the server thread.
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            flushPendingObservations();
            flushExcavationLedger();
        }, flushTicks, flushTicks);

        // Idle-session cleanup and in-memory ledger pruning.
        getServer().getScheduler().runTaskTimer(this, this::pruneSessions, 600L, 600L);

        // Retention, on a worker because every statement blocks.
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::pruneRetention, 1200L, 72000L);

        if (!persistence.isAvailable()) {
            long retryTicks = databaseRetrySeconds * 20L;
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::attemptDatabaseRecovery,
                    retryTicks, retryTicks);
        }

        var policy = settings.decisionPolicy();
        if (policy.mode() == EnforcementMode.BAN_WAVE && policy.banWave().enabled()
                && policy.banWave().automaticBan()) {
            long waveTicks = policy.banWave().intervalMinutes() * 60L * 20L;
            getServer().getScheduler().runTaskTimer(this, this::runAutomaticWave, waveTicks, waveTicks);
        }
    }

    /** Builds a window for every active session and submits it for evaluation. */
    private void analyseActiveSessions() {
        if (!settings.analysisEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (var session : sessions.all()) {
            if (session.hasAnalysableActivity()) {
                analysis.submit(session.snapshot(now));
            }
        }
    }

    private void flushPendingObservations() {
        if (!persistence.isAvailable()) {
            return;
        }
        int batch = settings.performance().persistenceBatchSize();
        List<Observation.Mining> mining = pending.drainMining(batch);
        List<PendingPersistence.DiscoveryRecord> discoveries = pending.drainDiscoveries(batch);
        if (mining.isEmpty() && discoveries.isEmpty()) {
            return;
        }
        try {
            if (!mining.isEmpty()) {
                persistence.miningEvents().saveAll(mining);
            }
            for (PendingPersistence.DiscoveryRecord record : discoveries) {
                persistence.oreDiscoveries().save(record.playerId(), record.worldKey(), null,
                        record.discovery());
            }
        } catch (PersistenceException e) {
            // The observations were drained before the write, so they cannot be retried without
            // unbounded buffering. They are counted as dropped so the loss is visible rather than
            // silent, and the store is marked unavailable so recovery takes over.
            pending.recordDropped(mining.size(), discoveries.size());
            persistence.markUnavailable();
            LOGGER.error("Dropped {} mining event(s) and {} discovery(ies); the store is now unavailable",
                    mining.size(), discoveries.size(), e);
        }
    }

    private void flushExcavationLedger() {
        if (!persistence.isAvailable()) {
            return;
        }
        var removals = ledger.drainPending(settings.performance().persistenceBatchSize());
        if (removals.isEmpty()) {
            return;
        }
        try {
            persistence.worldModifications().recordAll(removals);
        } catch (PersistenceException e) {
            persistence.markUnavailable();
            LOGGER.error("Dropped {} excavation record(s); the store is now unavailable",
                    removals.size(), e);
        }
    }

    /** Drops idle players' buffers and prunes the in-memory excavation ledger. */
    private void pruneSessions() {
        Instant now = Instant.now();
        var removed = sessions.pruneIdle(now,
                Duration.ofMinutes(settings.tracking().sessionIdleTimeoutMinutes()));
        for (var session : removed) {
            if (session.hasAnalysableActivity()) {
                // Finalising on prune rather than discarding means a player who went idle mid-session
                // still has their evidence assessed.
                analysis.submit(session.snapshot(now));
            }
        }
        ledger.pruneOlderThan(now.minus(Duration.ofHours(settings.performance().ledgerRetentionHours())));
    }

    /** Applies the retention policy. Every statement blocks, so this runs on a worker. */
    private void pruneRetention() {
        if (!persistence.isAvailable() || !settings.retention().enabled()) {
            return;
        }
        if (LocalTime.now(ZoneId.systemDefault()).getHour() != settings.retention().pruneHour()) {
            // A coarse guard: the task runs periodically and only acts during the configured hour,
            // which keeps retention off the busiest part of the day without needing a calendar.
            return;
        }
        Instant now = Instant.now();
        try {
            long mining = persistence.miningEvents().deleteOlderThan(
                    now.minus(Duration.ofDays(settings.retention().miningEventsDays())));
            long discoveries = persistence.oreDiscoveries().deleteOlderThan(
                    now.minus(Duration.ofDays(settings.retention().oreDiscoveriesDays())));
            long snapshots = persistence.suspicion().deleteOlderThan(
                    now.minus(Duration.ofDays(settings.retention().suspicionSnapshotsDays())));
            long modifications = persistence.worldModifications().deleteOlderThan(
                    now.minus(Duration.ofDays(settings.retention().worldModificationsDays())));
            LOGGER.info("Retention pruned {} mining event(s), {} discovery(ies), {} snapshot(s), "
                    + "{} excavation record(s)", mining, discoveries, snapshots, modifications);
        } catch (PersistenceException e) {
            LOGGER.error("Retention pruning failed", e);
        }
    }

    private void attemptDatabaseRecovery() {
        if (persistence.attemptRecovery(migrationsOnStartup)) {
            this.schemaVersion = persistence.schemaVersion();
            runSync(() -> analysis.loadStoredCandidates());
        }
    }

    private void runAutomaticWave() {
        var decision = analysis.planWave(Instant.now());
        if (!decision.shouldRun()) {
            return;
        }
        var plan = decision.planIfPresent().orElseThrow();
        enforcement.executeWave(plan, null);
        analysis.recordWaveExecuted(plan, Instant.now());
    }

    // -------------------------------------------------------------------------------------
    // Accessors used by the command layer
    // -------------------------------------------------------------------------------------

    public PluginSettings settings() {
        return settings;
    }

    public MessageService messages() {
        return messages;
    }

    public PersistenceBundle persistence() {
        return persistence;
    }

    public AnalysisService analysis() {
        return analysis;
    }

    public SessionRegistry sessions() {
        return sessions;
    }

    public PendingPersistence pending() {
        return pending;
    }

    public ExcavationLedger ledger() {
        return ledger;
    }

    public GuiManager gui() {
        return gui;
    }

    public EnforcementService enforcement() {
        return enforcement;
    }

    public boolean suspicionEnabled() {
        return settings.suspicionEnabled();
    }

    public boolean usesVirtualThreads() {
        return settings.performance().useVirtualThreads();
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** Schedules a task on a worker thread. */
    public void runAsync(Runnable task) {
        workers.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // A failed background task is logged, never propagated: an exception escaping into a
                // thread pool would otherwise be swallowed silently.
                LOGGER.error("Background task failed", e);
            }
        });
    }

    /** Schedules a task back onto the Minecraft server thread. */
    public void runSync(Runnable task) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    /**
     * Reloads configuration.
     *
     * <p>In-memory observations are deliberately kept. Discarding every player's accumulated evidence
     * because an administrator changed a message string would be a far worse outcome than the
     * discontinuity it was meant to avoid; what changes is the model applied from now on.
     *
     * @return null on success, or a message describing the failure
     */
    public String reloadConfiguration() {
        reloadConfig();
        warnIfConfigIsNewer("config.yml", getConfig());
        ConfigLoader loader = new ConfigLoader();
        try {
            ConfigLoader.LoadResult result = loader.load(getConfig());
            this.settings = result.settings();
            this.debugEnabled = settings.debug().enabled();
            for (String warning : result.warnings()) {
                LOGGER.warn("Configuration: {}", warning);
            }
        } catch (ConfigLoader.FatalConfigurationException e) {
            // Reported to the console as well as to whoever ran the command: a failed reload leaves the
            // previous configuration in force, and anyone reading the console later needs to have seen it.
            console.line("lifecycle.reload-failed", Map.of("error", e.getMessage()));
            return e.getMessage();
        }
        loadMessageAndGuiConfiguration();
        console.line("lifecycle.reload-success", consolePlaceholders());
        return null;
    }

    /** Toggles debug logging, returning the new state. */
    public boolean toggleDebug() {
        this.debugEnabled = !debugEnabled;
        return debugEnabled;
    }
}
