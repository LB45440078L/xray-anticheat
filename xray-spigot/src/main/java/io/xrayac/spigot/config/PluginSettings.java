package io.xrayac.spigot.config;

import io.xrayac.core.alert.DiscordConfig;
import io.xrayac.core.analysis.HistoryParameters;
import io.xrayac.core.config.MapOreProfileRegistry;
import io.xrayac.core.config.OreProfile;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.enforcement.EnforcementCommands;
import io.xrayac.core.evidence.EvidenceParameters;
import io.xrayac.core.decision.DecisionPolicy;
import io.xrayac.core.port.OreCatalog;
import io.xrayac.core.world.ExposurePolicy;
import io.xrayac.core.world.MapOreCatalog;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The validated, typed configuration of the whole plugin.
 *
 * <p>Everything the plugin does is decided by this object. It is assembled once at startup — and
 * again on {@code /xray reload} — by {@link ConfigLoader}, from {@code config.yml}. Nothing below
 * this layer reads YAML: the analytical core receives plain records, which is what allows the entire
 * engine to be tested without a server or a configuration file.
 *
 * <p>Immutable, so a reload swaps the whole object atomically. A partially-applied reload would mean
 * some players' assessments were made under one set of ore priors and some under another, which
 * would make the stored evidence incomparable and the audit trail misleading.
 *
 * @param analysisEnabled  whether the analytical engine runs at all
 * @param analysedWorlds   world names to observe, or {@code ["*"]} for all
 * @param exposurePolicy   how ore visibility is judged
 * @param evidenceParameters global statistical parameters
 * @param decisionPolicy   how evidence maps onto enforcement
 * @param oreProfiles      per-ore models
 * @param tracking         in-memory tracking limits
 * @param performance      worker and I/O tuning
 * @param retention        how long stored data is kept
 * @param debug            diagnostics
 */
public record PluginSettings(
        boolean analysisEnabled,
        boolean suspicionEnabled,
        Set<String> analysedWorlds,
        ExposurePolicy exposurePolicy,
        EvidenceParameters evidenceParameters,
        DecisionPolicy decisionPolicy,
        List<OreProfile> oreProfiles,
        Tracking tracking,
        Performance performance,
        Retention retention,
        History history,
        Alerts alerts,
        EnforcementCommands enforcementCommands,
        Debug debug) {

    public PluginSettings {
        analysedWorlds = Set.copyOf(analysedWorlds);
        oreProfiles = List.copyOf(oreProfiles);
        if (analysedWorlds.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one analysed world is required; use [\"*\"] to analyse every world");
        }
    }

    /** The registry the evidence components consult for per-ore parameters. */
    public OreProfileRegistry oreProfileRegistry() {
        return MapOreProfileRegistry.of(oreProfiles);
    }

    /** The catalogue the vein analyser consults to map block keys onto canonical ore ids. */
    public OreCatalog oreCatalog() {
        MapOreCatalog.Builder builder = MapOreCatalog.builder();
        for (OreProfile profile : oreProfiles) {
            if (!profile.enabled()) {
                // A disabled ore is not merely ignored downstream — it is excluded from the block
                // mapping entirely, so its blocks are never reconstructed as veins and never enter
                // the statistics. Doing otherwise would silently analyse an ore the administrator
                // had switched off.
                continue;
            }
            for (String blockKey : profile.blockKeys()) {
                builder.map(blockKey, profile.oreId());
            }
        }
        return builder.build();
    }

    /** Ore profiles that are enabled, for startup logging and status reporting. */
    public List<OreProfile> enabledOres() {
        return oreProfiles.stream().filter(OreProfile::enabled).toList();
    }

    /** Whether the given world name is observed. */
    public boolean analysesWorld(String worldName) {
        return analysedWorlds.contains("*") || analysedWorlds.contains(worldName);
    }

    /**
     * How far back an assessment reads the stored record.
     *
     * <p>Without this an assessment only ever described the current login, because the in-memory window
     * is all the engine saw. With it, evidence accumulates over a player's whole recorded time on the
     * server, which is the premise of a defensible ban wave.
     *
     * @param enabled         false restores session-only analysis
     * @param lookbackDays    how far back to read; should not exceed
     *                        {@code retention.mining-events-days}, or the trajectory history will be
     *                        thinner than requested
     * @param maxDiscoveries  per-player read limit, bounding one assessment's cost
     * @param maxMiningEvents per-player read limit for rebuilding the historical trajectory
     * @param refreshMinutes  how long a hydrated history is reused before it is read again
     */
    public record History(
            boolean enabled,
            int lookbackDays,
            int maxDiscoveries,
            int maxMiningEvents,
            int refreshMinutes) {

        public History {
            if (lookbackDays < 1) {
                throw new IllegalArgumentException("history lookback must be at least one day");
            }
            if (maxDiscoveries < 1 || maxMiningEvents < 1) {
                throw new IllegalArgumentException("history read limits must be at least 1");
            }
            if (refreshMinutes < 1) {
                throw new IllegalArgumentException("history refresh must be at least one minute");
            }
        }

        /** The core-level parameters the hydrator consumes. */
        public HistoryParameters toParameters() {
            return new HistoryParameters(enabled, Duration.ofDays(lookbackDays), maxDiscoveries,
                    maxMiningEvents, Duration.ofMinutes(refreshMinutes));
        }
    }

    /** The shipped history defaults, used when the configuration omits the section. */
    public static History defaultHistory() {
        return new History(true, 90, 2000, 20000, 15);
    }

    /** Bounded in-memory tracking limits. */
    public record Tracking(
            double movementSampleDistance,
            int pathBufferSize,
            int miningBufferSize,
            int discoveryHistorySize,
            int analysisIntervalMinutes,
            int sessionIdleTimeoutMinutes) {

        public Tracking {
            if (movementSampleDistance <= 0) {
                throw new IllegalArgumentException("movementSampleDistance must be > 0");
            }
            if (pathBufferSize < 2) {
                // Fewer than two path points cannot describe a direction, so the geometry engine
                // would have nothing to work with and every path metric would be meaningless.
                throw new IllegalArgumentException("pathBufferSize must be at least 2");
            }
            if (miningBufferSize < 1 || discoveryHistorySize < 1) {
                throw new IllegalArgumentException("buffer sizes must be at least 1");
            }
            if (analysisIntervalMinutes < 1) {
                throw new IllegalArgumentException("analysisIntervalMinutes must be at least 1");
            }
            if (sessionIdleTimeoutMinutes < 1) {
                throw new IllegalArgumentException("sessionIdleTimeoutMinutes must be at least 1");
            }
        }
    }

    /** Worker and persistence tuning. */
    public record Performance(
            int analysisThreads,
            boolean useVirtualThreads,
            int persistenceBatchSize,
            int flushIntervalSeconds,
            int maxVeinSize,
            int ledgerMaxEntries,
            int ledgerRetentionHours) {

        public Performance {
            if (analysisThreads < 1) {
                throw new IllegalArgumentException("analysisThreads must be at least 1");
            }
            if (persistenceBatchSize < 1) {
                throw new IllegalArgumentException("persistenceBatchSize must be at least 1");
            }
            if (flushIntervalSeconds < 1) {
                throw new IllegalArgumentException("flushIntervalSeconds must be at least 1");
            }
            if (maxVeinSize < 1 || maxVeinSize > 1024) {
                throw new IllegalArgumentException("maxVeinSize must lie in [1, 1024]");
            }
            if (ledgerMaxEntries < 1) {
                throw new IllegalArgumentException("ledgerMaxEntries must be at least 1");
            }
            if (ledgerRetentionHours < 1) {
                throw new IllegalArgumentException("ledgerRetentionHours must be at least 1");
            }
        }
    }

    /** Data retention. */
    public record Retention(
            boolean enabled,
            int miningEventsDays,
            int oreDiscoveriesDays,
            int suspicionSnapshotsDays,
            int worldModificationsDays,
            int pruneHour) {

        public Retention {
            if (pruneHour < 0 || pruneHour > 23) {
                throw new IllegalArgumentException("pruneHour must lie in [0, 23]");
            }
            if (miningEventsDays < 1 || oreDiscoveriesDays < 1 || suspicionSnapshotsDays < 1
                    || worldModificationsDays < 1) {
                throw new IllegalArgumentException("retention periods must be at least one day");
            }
        }
    }

    /** Diagnostics. Only keys that actually affect behaviour are present. */
    public record Debug(boolean enabled, boolean logAnalysis) {
    }

    /**
     * How staff are told about what the plugin finds.
     *
     * @param throttleMinutes how long one player's alerts are suppressed for after one is delivered,
     *                        so that a persistently flagged player does not flood the staff channel;
     *                        zero disables throttling and sends every alert
     * @param discord         forwarding to a Discord channel webhook
     */
    public record Alerts(int throttleMinutes, DiscordConfig discord) {

        public Alerts {
            if (throttleMinutes < 0) {
                throw new IllegalArgumentException("alerts.throttle-minutes must not be negative");
            }
            discord = discord == null ? DiscordConfig.disabled() : discord;
        }

        /** The suppression window as a duration. */
        public Duration throttle() {
            return Duration.ofMinutes(throttleMinutes);
        }

        /** The shipped defaults, used when the section is absent. */
        public static Alerts defaults() {
            return new Alerts(5, DiscordConfig.disabled());
        }
    }

    /** A builder used by the loader, so that a partially-invalid file can fall back per-key. */
    public static Set<String> normaliseWorldList(List<String> raw) {
        Set<String> worlds = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry != null && !entry.isBlank()) {
                worlds.add(entry.trim());
            }
        }
        if (worlds.isEmpty()) {
            worlds.add("*");
        }
        return worlds;
    }
}
