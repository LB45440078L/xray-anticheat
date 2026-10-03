package io.xrayac.spigot.config;

import io.xrayac.web.WebConfig;
import io.xrayac.core.config.MapOreProfileRegistry;
import io.xrayac.core.config.OreProfile;
import io.xrayac.core.decision.DecisionPolicy;
import io.xrayac.core.decision.EnforcementMode;
import io.xrayac.core.evidence.EvidenceParameters;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.world.ExposurePolicy;
import io.xrayac.persistence.DatabaseConfig;
import io.xrayac.persistence.Dialect;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Translates {@code config.yml} and {@code database.yml} into validated, typed settings.
 *
 * <h2>Validation strategy: fall back per key, refuse only what cannot work</h2>
 * An administrator who mistypes one number should not lose the whole plugin. Each section is built
 * independently and, if it fails validation, the loader substitutes the built-in default, records a
 * clear warning naming the offending path, and carries on. That is the behaviour that lets someone
 * fix a typo with {@code /xray reload} instead of a restart.
 *
 * <p>A small number of failures <b>are</b> fatal, and only those: configurations that would leave
 * the plugin with nothing to do. If no ore is enabled, or an enabled ore declares no block keys,
 * the analytical engine has no inputs and silently running would be worse than refusing, because an
 * administrator would believe X-ray detection was active when it could never fire.
 *
 * <p>Nothing is silently accepted. Every defaulted or rejected value produces a warning through
 * {@link LoadResult#warnings()}, which the plugin logs at startup and on reload.
 */
public final class ConfigLoader {

    /** Thrown only for configurations that cannot produce a working plugin. */
    public static class FatalConfigurationException extends Exception {
        private static final long serialVersionUID = 1L;

        public FatalConfigurationException(String message) {
            super(message);
        }
    }

    /**
     * The outcome of a load: the settings, plus everything the administrator should know about.
     *
     * @param settings the validated settings
     * @param warnings one human-readable line per defaulted or rejected value
     */
    public record LoadResult(PluginSettings settings, List<String> warnings) {

        public LoadResult {
            warnings = List.copyOf(warnings);
        }
    }

    /** Built-in profiles, used as per-ore fallbacks when an administrator's entry is invalid. */
    private static final Map<String, OreProfile> BUILT_IN_DEFAULTS =
            MapOreProfileRegistry.defaults().stream()
                    .collect(Collectors.toMap(OreProfile::oreId, Function.identity()));

    private final List<String> warnings = new ArrayList<>();

    public LoadResult load(FileConfiguration config) throws FatalConfigurationException {
        warnings.clear();

        boolean analysisEnabled = config.getBoolean("analysis.enabled", true);
        boolean suspicionEnabled = config.getBoolean("suspicion.enabled", true);

        Set<String> worlds = PluginSettings.normaliseWorldList(
                config.getStringList("analysis.worlds"));

        ExposurePolicy exposure = loadExposure(config);
        EvidenceParameters evidence = loadEvidence(config);
        DecisionPolicy decision = loadDecision(config);
        List<OreProfile> ores = loadOres(config);

        List<OreProfile> enabled = ores.stream().filter(OreProfile::enabled).toList();
        if (enabled.isEmpty()) {
            throw new FatalConfigurationException(
                    "no ore is enabled under 'ores', so the plugin would collect data it can never "
                            + "analyse. Enable at least one ore (for example 'ores.diamond.enabled: true').");
        }
        for (OreProfile profile : enabled) {
            if (profile.blockKeys().isEmpty()) {
                throw new FatalConfigurationException("ore '" + profile.oreId()
                        + "' is enabled but declares no block-keys, so none of its blocks could ever "
                        + "be recognised");
            }
        }

        PluginSettings.Tracking tracking = loadTracking(config);
        PluginSettings.Performance performance = loadPerformance(config);
        PluginSettings.Retention retention = loadRetention(config);
        PluginSettings.History history = loadHistory(config);
        PluginSettings.Debug debug = loadDebug(config);

        // Reading back further than the retention keeps would silently analyse a shorter past than the
        // administrator asked for, so the mismatch is reported rather than left to be discovered as an
        // unexplained cap on the evidence.
        if (history.enabled() && history.lookbackDays() > retention.miningEventsDays()) {
            warnings.add("analysis.history.lookback-days (" + history.lookbackDays()
                    + ") exceeds retention.mining-events-days (" + retention.miningEventsDays()
                    + "), so the stored trajectory — and therefore the tunnel-geometry signal — will "
                    + "only reach back " + retention.miningEventsDays() + " day(s). Discoveries are "
                    + "kept for " + retention.oreDiscoveriesDays() + " day(s) and are unaffected. Raise "
                    + "retention.mining-events-days to match, or lower the lookback.");
        }

        PluginSettings settings = new PluginSettings(analysisEnabled, suspicionEnabled, worlds,
                exposure, evidence, decision, ores, tracking, performance, retention, history, debug);
        return new LoadResult(settings, warnings);
    }

    // ---------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------

    private ExposurePolicy loadExposure(FileConfiguration config) {
        ExposurePolicy fallback = ExposurePolicy.defaults();
        try {
            return new ExposurePolicy(
                    config.getBoolean("analysis.exposure.diagonal-visibility",
                            fallback.diagonalVisibility()),
                    secondsToMillis(config.getLong("analysis.exposure.recent-excavation-window-seconds",
                            fallback.recentExcavationWindowMillis() / 1000L)),
                    config.getInt("analysis.exposure.occupied-space-radius",
                            fallback.occupiedSpaceRadius()),
                    config.getBoolean("analysis.exposure.excavation-attribution-required",
                            fallback.excavationAttributionRequired()));
        } catch (IllegalArgumentException e) {
            warnings.add("analysis.exposure: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private EvidenceParameters loadEvidence(FileConfiguration config) {
        EvidenceParameters fallback = EvidenceParameters.defaults();
        try {
            double priorProbability = config.getDouble("analysis.evidence.prior-probability", 0.02);
            if (!(priorProbability > 0.0) || !(priorProbability < 1.0)) {
                warnings.add("analysis.evidence.prior-probability must lie strictly in (0, 1); "
                        + "using " + 0.02);
                priorProbability = 0.02;
            }
            return new EvidenceParameters(
                    // The configuration speaks in probabilities because that is how an administrator
                    // thinks about a prior; the engine works in log-odds, and the conversion belongs
                    // here rather than being demanded of the person editing the file.
                    Math.log(priorProbability / (1.0 - priorProbability)),
                    config.getDouble("analysis.evidence.half-life-hours", fallback.halfLifeHours()),
                    config.getDouble("analysis.evidence.sample-size-shrink-k",
                            fallback.sampleSizeShrinkConstant()),
                    config.getDouble("analysis.evidence.confidence-sample-scale",
                            fallback.confidenceSampleScale()),
                    config.getDouble("analysis.evidence.confidence-group-scale",
                            fallback.confidenceGroupScale()),
                    config.getInt("analysis.evidence.minimum-sample-size",
                            fallback.minimumSampleSize()),
                    config.getInt("analysis.evidence.minimum-independent-groups",
                            fallback.minimumIndependentGroups()),
                    config.getDouble("analysis.evidence.lookback-distance-blocks",
                            fallback.lookbackDistanceBlocks()),
                    config.getDouble("analysis.evidence.legitimate-hidden-fraction",
                            fallback.legitimateHiddenFraction()),
                    config.getDouble("analysis.evidence.ore-informed-hidden-fraction",
                            fallback.oreInformedHiddenFraction()));
        } catch (IllegalArgumentException e) {
            warnings.add("analysis.evidence: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private DecisionPolicy loadDecision(FileConfiguration config) {
        DecisionPolicy fallback = DecisionPolicy.defaults();
        try {
            EnforcementMode mode = enumOrDefault(config, "suspicion.enforcement-mode",
                    EnforcementMode.class, fallback.mode());
            DecisionPolicy.BanWavePolicy banWave = loadBanWave(config);

            return new DecisionPolicy(
                    mode,
                    config.getDouble("suspicion.minimum-confidence-for-alert",
                            fallback.minimumConfidenceForAlert()),
                    strength(config, "suspicion.minimum-strength-for-alert",
                            fallback.minimumStrengthForAlert()),
                    strength(config, "suspicion.minimum-strength-for-flag",
                            fallback.minimumStrengthForFlag()),
                    strength(config, "suspicion.minimum-strength-for-kick",
                            fallback.minimumStrengthForKick()),
                    strength(config, "suspicion.minimum-strength-for-ban",
                            fallback.minimumStrengthForBan()),
                    config.getDouble("suspicion.minimum-confidence-for-ban",
                            fallback.minimumConfidenceForBan()),
                    banWave);
        } catch (IllegalArgumentException e) {
            warnings.add("suspicion: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private DecisionPolicy.BanWavePolicy loadBanWave(FileConfiguration config) {
        DecisionPolicy.BanWavePolicy fallback = DecisionPolicy.BanWavePolicy.defaults();
        try {
            return new DecisionPolicy.BanWavePolicy(
                    config.getBoolean("ban-wave.enabled", fallback.enabled()),
                    config.getLong("ban-wave.interval-minutes", fallback.intervalMinutes()),
                    config.getLong("ban-wave.candidate-ttl-hours", fallback.candidateTtlHours()),
                    strength(config, "ban-wave.minimum-strength", fallback.minimumStrength()),
                    config.getDouble("ban-wave.minimum-confidence", fallback.minimumConfidence()),
                    config.getInt("ban-wave.minimum-independent-signals",
                            fallback.minimumIndependentSignals()),
                    config.getInt("ban-wave.minimum-candidates", fallback.minimumCandidates()),
                    config.getBoolean("ban-wave.automatic-ban", fallback.automaticBan()));
        } catch (IllegalArgumentException e) {
            warnings.add("ban-wave: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private List<OreProfile> loadOres(FileConfiguration config) {
        List<OreProfile> profiles = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("ores");
        if (section == null) {
            warnings.add("no 'ores' section found; the built-in ore defaults are being used");
            return MapOreProfileRegistry.defaults();
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection ore = section.getConfigurationSection(key);
            if (ore == null) {
                continue;
            }
            // The section key is the ore id (diamond, emerald, ancient_debris). A key that matches a
            // built-in profile inherits that profile's values for anything left unset; a genuinely new
            // ore must specify its own, because inventing a plausible baseline for it would silently
            // become the yardstick by which somebody is judged.
            OreProfile fallback = BUILT_IN_DEFAULTS.get(key.toLowerCase(Locale.ROOT));
            profiles.add(loadOre(key, ore, fallback));
        }

        if (profiles.isEmpty()) {
            warnings.add("the 'ores' section contained no readable entries; using built-in defaults");
            return MapOreProfileRegistry.defaults();
        }
        return profiles;
    }

    /**
     * Loads one ore, falling back to a built-in profile only when this ore matches a known one.
     *
     * <p>An entirely new ore with a bad value cannot be repaired from a built-in default — there is
     * none — so it is disabled with a warning rather than guessed at. Inventing plausible numbers for
     * an ore the administrator defined would be worse than declaring it unconfigured, because the
     * invented rate would silently become the baseline for someone's ban.
     */
    private OreProfile loadOre(String key, ConfigurationSection ore, OreProfile fallback) {
        String oreId = fallback != null ? fallback.oreId() : key.toLowerCase(Locale.ROOT);
        String displayName = ore.getString("display-name",
                fallback != null ? fallback.displayName() : key);
        boolean enabled = ore.getBoolean("enabled", fallback != null && fallback.enabled());

        List<String> blockKeys = ore.getStringList("block-keys");
        if (blockKeys.isEmpty() && fallback != null) {
            blockKeys = List.copyOf(fallback.blockKeys());
        }

        OreProfile.Builder builder = OreProfile.builder(oreId, displayName)
                .enabled(enabled)
                .blockKeys(blockKeys.toArray(new String[0]));

        try {
            builder.hiddenDiscoveryRatePerThousandBlocks(doubleOr(
                    ore, "hidden-discovery-rate-per-1000-blocks", fallback, OreProfile::hiddenDiscoveryRatePerThousandBlocks));
            builder.oreInformedRateMultiplier(doubleOr(
                    ore, "ore-informed-rate-multiplier", fallback, OreProfile::oreInformedRateMultiplier));
            builder.yRange(intOr(ore, "min-y", fallback, OreProfile::minY),
                    intOr(ore, "max-y", fallback, OreProfile::maxY));
            builder.typicalVeinSize(intOr(ore, "typical-vein-size", fallback, OreProfile::typicalVeinSize));
            builder.expectedDistanceBetweenDiscoveries(doubleOr(
                    ore, "expected-distance-between-discoveries", fallback,
                    OreProfile::expectedDistanceBetweenDiscoveries));
            builder.targetingAlignmentThresholdDegrees(doubleOr(
                    ore, "alignment-threshold-degrees", fallback,
                    OreProfile::targetingAlignmentThresholdDegrees));
            builder.moveAlignmentProbabilities(
                    doubleOr(ore, "legitimate-move-alignment-probability", fallback,
                            OreProfile::legitimateMoveAlignmentProbability),
                    doubleOr(ore, "informed-move-alignment-probability", fallback,
                            OreProfile::informedMoveAlignmentProbability));
            builder.lookAlignmentProbabilities(
                    doubleOr(ore, "legitimate-look-alignment-probability", fallback,
                            OreProfile::legitimateLookAlignmentProbability),
                    doubleOr(ore, "informed-look-alignment-probability", fallback,
                            OreProfile::informedLookAlignmentProbability));
            builder.evidenceWeight(doubleOr(ore, "evidence-weight", fallback, OreProfile::evidenceWeight));
            return builder.build();
        } catch (IllegalArgumentException e) {
            if (fallback != null) {
                warnings.add("ores." + key + ": " + e.getMessage()
                        + " — falling back to the built-in " + oreId + " profile");
                return fallback;
            }
            warnings.add("ores." + key + ": " + e.getMessage()
                    + " — the ore is disabled because there is no built-in profile to fall back to. "
                    + "It is NOT hidden from the analysis: it simply will not be modelled.");
            return OreProfile.builder(oreId, displayName)
                    .enabled(false)
                    .blockKeys(blockKeys.toArray(new String[0]))
                    .hiddenDiscoveryRatePerThousandBlocks(1.0)
                    .oreInformedRateMultiplier(2.0)
                    .build();
        }
    }

    private PluginSettings.Tracking loadTracking(FileConfiguration config) {
        PluginSettings.Tracking fallback = new PluginSettings.Tracking(
                0.5, 512, 2048, 200, 5, 30, false);
        try {
            return new PluginSettings.Tracking(
                    config.getDouble("tracking.movement-sample-distance",
                            fallback.movementSampleDistance()),
                    config.getInt("tracking.path-buffer-size", fallback.pathBufferSize()),
                    config.getInt("tracking.mining-buffer-size", fallback.miningBufferSize()),
                    config.getInt("tracking.discovery-history-size", fallback.discoveryHistorySize()),
                    config.getInt("tracking.analysis-interval-minutes",
                            fallback.analysisIntervalMinutes()),
                    config.getInt("tracking.session-idle-timeout-minutes",
                            fallback.sessionIdleTimeoutMinutes()),
                    config.getBoolean("tracking.retain-disconnected-players",
                            fallback.retainDisconnectedPlayers()));
        } catch (IllegalArgumentException e) {
            warnings.add("tracking: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private PluginSettings.Performance loadPerformance(FileConfiguration config) {
        PluginSettings.Performance fallback = new PluginSettings.Performance(
                4, true, 500, 10, 64, 2_000_000, 72);
        try {
            return new PluginSettings.Performance(
                    config.getInt("performance.analysis-threads", fallback.analysisThreads()),
                    config.getBoolean("performance.use-virtual-threads", fallback.useVirtualThreads()),
                    config.getInt("performance.persistence-batch-size",
                            fallback.persistenceBatchSize()),
                    config.getInt("performance.flush-interval-seconds",
                            fallback.flushIntervalSeconds()),
                    config.getInt("performance.max-vein-size", fallback.maxVeinSize()),
                    config.getInt("performance.ledger-max-entries", fallback.ledgerMaxEntries()),
                    config.getInt("performance.ledger-retention-hours",
                            fallback.ledgerRetentionHours()));
        } catch (IllegalArgumentException e) {
            warnings.add("performance: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private PluginSettings.Retention loadRetention(FileConfiguration config) {
        PluginSettings.Retention fallback = new PluginSettings.Retention(
                true, 7, 90, 180, 30, 365, 4);
        try {
            return new PluginSettings.Retention(
                    config.getBoolean("retention.enabled", fallback.enabled()),
                    config.getInt("retention.mining-events-days", fallback.miningEventsDays()),
                    config.getInt("retention.ore-discoveries-days", fallback.oreDiscoveriesDays()),
                    config.getInt("retention.suspicion-snapshots-days",
                            fallback.suspicionSnapshotsDays()),
                    config.getInt("retention.world-modifications-days",
                            fallback.worldModificationsDays()),
                    config.getInt("retention.ban-waves-days", fallback.banWavesDays()),
                    config.getInt("retention.prune-hour", fallback.pruneHour()));
        } catch (IllegalArgumentException e) {
            warnings.add("retention: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private PluginSettings.History loadHistory(FileConfiguration config) {
        PluginSettings.History fallback = PluginSettings.defaultHistory();
        try {
            return new PluginSettings.History(
                    config.getBoolean("analysis.history.enabled", fallback.enabled()),
                    config.getInt("analysis.history.lookback-days", fallback.lookbackDays()),
                    config.getInt("analysis.history.max-discoveries", fallback.maxDiscoveries()),
                    config.getInt("analysis.history.max-mining-events", fallback.maxMiningEvents()),
                    config.getInt("analysis.history.refresh-minutes", fallback.refreshMinutes()));
        } catch (IllegalArgumentException e) {
            warnings.add("analysis.history: " + e.getMessage() + " — using defaults");
            return fallback;
        }
    }

    private PluginSettings.Debug loadDebug(FileConfiguration config) {
        return new PluginSettings.Debug(
                config.getBoolean("debug.enabled", false),
                config.getBoolean("debug.log-analysis", false));
    }

    // ---------------------------------------------------------------------------------------
    // Database
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the database configuration.
     *
     * <p>A password may be supplied in the file, but the environment variable named by
     * {@code password-environment-variable} takes precedence when set. Configuration files end up in
     * backups, in version control and in support bundles; credentials should not.
     */
    public DatabaseConfig loadDatabase(FileConfiguration config) {
        String type = config.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        int maximumPoolSize = config.getInt("storage.pool.maximum-pool-size", 10);
        int minimumIdle = config.getInt("storage.pool.minimum-idle", 2);
        long timeout = config.getLong("storage.pool.connection-timeout-millis", 10_000L);
        int batchSize = config.getInt("storage.pool.batch-size", 500);

        try {
            return switch (type) {
                case "sqlite", "sqlite3" -> new DatabaseConfig(
                        "jdbc:sqlite:" + config.getString("storage.sqlite.file",
                                "plugins/XRayAntiCheat/xray.db"),
                        "", "",
                        Math.min(maximumPoolSize, 4),
                        Math.min(minimumIdle, 1),
                        timeout, batchSize);
                case "mariadb", "mysql" -> new DatabaseConfig(
                        "jdbc:mariadb://" + config.getString("storage.mariadb.host", "localhost")
                                + ":" + config.getInt("storage.mariadb.port", 3306)
                                + "/" + config.getString("storage.mariadb.database", "xray_anticheat")
                                + parametersSuffix(config, "storage.mariadb.connection-parameters"),
                        config.getString("storage.mariadb.username", "xray"),
                        resolvePassword(config, "storage.mariadb"),
                        maximumPoolSize, minimumIdle, timeout, batchSize);
                case "postgresql", "postgres" -> new DatabaseConfig(
                        "jdbc:postgresql://" + config.getString("storage.postgresql.host", "localhost")
                                + ":" + config.getInt("storage.postgresql.port", 5432)
                                + "/" + config.getString("storage.postgresql.database", "xray_anticheat")
                                + parametersSuffix(config, "storage.postgresql.connection-parameters"),
                        config.getString("storage.postgresql.username", "xray"),
                        resolvePassword(config, "storage.postgresql"),
                        maximumPoolSize, minimumIdle, timeout, batchSize);
                default -> throw new IllegalArgumentException("unsupported storage type '" + type
                        + "'; expected one of: sqlite, mariadb, postgresql");
            };
        } catch (IllegalArgumentException e) {
            warnings.add("storage: " + e.getMessage() + " — falling back to embedded SQLite");
            return DatabaseConfig.sqlite("plugins/XRayAntiCheat/xray.db");
        }
    }

    private String resolvePassword(FileConfiguration config, String section) {
        String environmentVariable = config.getString(section + ".password-environment-variable", "");
        if (!environmentVariable.isBlank()) {
            String fromEnvironment = System.getenv(environmentVariable);
            if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
                return fromEnvironment;
            }
            warnings.add(section + ": environment variable '" + environmentVariable
                    + "' is not set; falling back to the password in the configuration file");
        }
        return config.getString(section + ".password", "");
    }

    private String parametersSuffix(FileConfiguration config, String path) {
        String parameters = config.getString(path, "");
        return parameters.isBlank() ? "" : "?" + parameters;
    }

    /** Whether migrations should run at startup. */
    /**
     * Reads the administration panel's configuration.
     *
     * <p>Every key falls back to {@link WebConfig#defaults()}, so an existing {@code config.yml} that
     * predates this section yields a disabled panel rather than a failure. That is the important
     * property: upgrading the plugin must not silently start an HTTP server that was never configured.
     *
     * @param config the loaded {@code config.yml}
     * @return the panel configuration, never null
     */
    public WebConfig loadWeb(FileConfiguration config) {
        WebConfig defaults = WebConfig.defaults();
        ConfigurationSection section = config.getConfigurationSection("web");
        if (section == null) {
            return defaults;
        }
        return new WebConfig(
                section.getBoolean("enabled", defaults.enabled()),
                section.getString("bind-address", defaults.bindAddress()),
                section.getInt("port", defaults.port()),
                section.getString("username", defaults.username()),
                section.getString("password-hash", defaults.passwordHash()),
                section.getInt("session-minutes", defaults.sessionMinutes()),
                section.getInt("max-failed-logins", defaults.maxFailedLogins()),
                section.getInt("lockout-minutes", defaults.lockoutMinutes()),
                section.getInt("page-size", defaults.pageSize()),
                section.getBoolean("allow-non-loopback", defaults.allowNonLoopback()),
                section.getBoolean("read-only", defaults.readOnly()),
                section.getBoolean("behind-proxy", defaults.behindProxy()));
    }

    /** Writes a new password hash for the panel into the configuration. */
    public void writeWebPasswordHash(FileConfiguration config, String hash) {
        config.set("web.password-hash", hash);
    }

    public boolean runMigrationsOnStartup(FileConfiguration databaseConfig) {
        return databaseConfig.getBoolean("storage.migrations.run-on-startup", true);
    }

    /** Whether the plugin tolerates an unreachable database. */
    public boolean continueWithoutDatabase(FileConfiguration databaseConfig) {
        return databaseConfig.getBoolean("storage.fail-safe.continue-without-database", true);
    }

    /** Seconds between reconnection attempts while the database is down. */
    public int databaseRetryIntervalSeconds(FileConfiguration databaseConfig) {
        return Math.max(5, databaseConfig.getInt("storage.fail-safe.retry-interval-seconds", 30));
    }

    /** The dialect implied by the database configuration. */
    public Dialect databaseDialect(FileConfiguration databaseConfig) {
        return loadDatabase(databaseConfig).dialect();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static long secondsToMillis(long seconds) {
        return seconds <= 0 ? 0L : seconds * 1000L;
    }

    private static <E extends Enum<E>> E enumOrDefault(FileConfiguration config, String path,
                                                       Class<E> type, E fallback) {
        String raw = config.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Handled by the caller's catch for IllegalArgumentException only when it is thrown; a
            // silent fallback here is deliberate so one bad enum does not reset the whole policy.
            return fallback;
        }
    }

    private static EvidenceStrength strength(FileConfiguration config, String path,
                                             EvidenceStrength fallback) {
        String raw = config.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return EvidenceStrength.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double doubleOr(ConfigurationSection section, String path, OreProfile fallback,
                                   java.util.function.ToDoubleFunction<OreProfile> extractor) {
        if (section.isSet(path)) {
            return section.getDouble(path);
        }
        if (fallback != null) {
            return extractor.applyAsDouble(fallback);
        }
        throw new IllegalArgumentException("'" + path + "' is required for a new ore");
    }

    private static int intOr(ConfigurationSection section, String path, OreProfile fallback,
                             java.util.function.ToIntFunction<OreProfile> extractor) {
        if (section.isSet(path)) {
            return section.getInt(path);
        }
        if (fallback != null) {
            return extractor.applyAsInt(fallback);
        }
        throw new IllegalArgumentException("'" + path + "' is required for a new ore");
    }

    /** Convenience for callers that want an Optional rather than an exception. */
    public Optional<PluginSettings> tryLoad(FileConfiguration config) {
        try {
            return Optional.of(load(config).settings());
        } catch (FatalConfigurationException e) {
            return Optional.empty();
        }
    }

    /** Exposes the collected warnings without performing a load, for tests. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
