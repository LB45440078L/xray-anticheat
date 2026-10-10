package io.xrayac.spigot.enforcement;

import io.xrayac.core.alert.AlertEvent;
import io.xrayac.core.decision.ActionType;
import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.decision.BanWavePlan;
import io.xrayac.core.decision.DecisionOutcome;
import io.xrayac.core.enforcement.CommandTemplate;
import io.xrayac.core.enforcement.EnforcementCommands;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.repository.PersistenceException;
import io.xrayac.core.statistics.LogOdds;
import io.xrayac.spigot.alert.DiscordNotifier;
import io.xrayac.spigot.message.MessageService;
import io.xrayac.spigot.persistence.PersistenceBundle;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries out enforcement: kicks, bans and ban waves.
 *
 * <h2>Every action is attributed and explained</h2>
 * A ban records who or what issued it and the evidence it rested on, in the server's own ban list and
 * in this plugin's audit tables. An automated ban whose reason is "X-Ray" with no numbers is
 * impossible to review a week later; the reason string therefore carries the verdict band, the
 * observation count and the number of independent signals, and the corresponding snapshot row
 * identifier is appended so the full report can be retrieved.
 *
 * <h2>Configurable commands</h2>
 * A server running its own punishment plugin can have this one run a command instead of writing to the
 * vanilla ban list. Two invariants survive that substitution, because they are what the action
 * <i>means</i> rather than how it is implemented: the player ends up disconnected, and this plugin still
 * writes its audit row. Everything else about the punishment — its duration, its scope, whether it is
 * recorded in another system — is the administrator's business.
 *
 * <h2>Server-thread confined</h2>
 * Every method here is called on the Minecraft server thread — from the scheduler for automated
 * enforcement, or directly from a command or GUI for a moderator's action. Persisting the moderator's
 * record is the exception, and so is the Discord notification: both are handed to a worker, because one
 * is a database write and the other is a network round trip.
 */
public final class EnforcementService
        implements java.util.function.BiConsumer<SuspicionSnapshot, DecisionOutcome> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnforcementService.class);

    private final Plugin plugin;
    private final MessageService messages;
    private final PersistenceBundle persistence;
    private final Consumer<Runnable> backgroundTask;
    private final EnforcementCommands commands;
    private final DiscordNotifier discord;

    public EnforcementService(Plugin plugin, MessageService messages, PersistenceBundle persistence,
                              Consumer<Runnable> backgroundTask, EnforcementCommands commands,
                              DiscordNotifier discord) {
        this.plugin = plugin;
        this.messages = messages;
        this.persistence = persistence;
        this.backgroundTask = backgroundTask;
        this.commands = commands;
        this.discord = discord;
    }

    /**
     * Automated enforcement for a single player, invoked by {@code AnalysisService}.
     *
     * <p>The decision outcome is required, not optional: the caller has already determined whether the
     * evidence justified a kick or a ban, and carrying out the wrong one would either under-enforce a
     * strong case or remove a player who should only have been disconnected.
     */
    @Override
    public void accept(SuspicionSnapshot snapshot, DecisionOutcome outcome) {
        Player target = Bukkit.getPlayer(snapshot.player().id());
        if (target == null) {
            return;
        }

        Map<String, String> placeholders = evidencePlaceholders(snapshot);

        if (outcome.action() == ActionType.BAN) {
            String reason = banReason(placeholders);
            placeholders.put("reason", reason);
            ban(snapshot.player().id(), snapshot.player().name(), reason, false, placeholders);
            recordModeratorAction(snapshot.player().id(), null, "AUTOMATED_BAN", snapshot.explain());
            discord.notify(AlertEvent.BAN, snapshot.evidenceStrength(), snapshot.statisticalConfidence(),
                    messages.get("alerts.discord.ban", placeholders));
            LOGGER.warn("Automatically banned {} — {} evidence, {} observation(s), {} independent "
                            + "signal(s), confidence {}",
                    snapshot.player().name(), snapshot.evidenceStrength().label(),
                    snapshot.sampleSize(), snapshot.independentGroups(),
                    MessageService.formatProbability(snapshot.statisticalConfidence()));
            return;
        }

        String reason = messages.get("enforcement.kick-reason", placeholders);
        placeholders.put("reason", reason);
        kick(target, reason, placeholders);
        discord.notify(AlertEvent.KICK, snapshot.evidenceStrength(), snapshot.statisticalConfidence(),
                messages.get("alerts.discord.kick", placeholders));
        LOGGER.info("Automatically disconnected {} on {} evidence ({} observation(s), {} signal(s))",
                snapshot.player().name(), snapshot.evidenceStrength().label(),
                snapshot.sampleSize(), snapshot.independentGroups());
    }

    /** A moderator kicks a player, from a command or the GUI. */
    public void kick(Player moderator, Player target, String reason) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", target.getName());
        placeholders.put("uuid", target.getUniqueId().toString());
        placeholders.put("moderator", moderator.getName());
        placeholders.put("reason", reason);
        placeholders.put("action", "KICK");

        kick(target, reason, placeholders);
        moderator.sendMessage(messages.get("actions.kick-manual", placeholders));
        recordModeratorAction(target.getUniqueId(), moderator.getUniqueId(), "KICK", reason);
    }

    /** A moderator bans a player, from a command or the GUI. */
    public void ban(Player moderator, OfflinePlayer target, String reason) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", name);
        placeholders.put("uuid", target.getUniqueId().toString());
        placeholders.put("moderator", moderator.getName());
        placeholders.put("reason", reason);
        placeholders.put("action", "BAN");

        ban(target.getUniqueId(), name, reason, false, placeholders);
        moderator.sendMessage(messages.get("actions.ban-manual", placeholders));
        recordModeratorAction(target.getUniqueId(), moderator.getUniqueId(), "BAN", reason);
    }

    /**
     * Executes a prepared ban wave.
     *
     * @param initiator the moderator who approved it, or null for a fully automatic wave
     */
    public void executeWave(BanWavePlan plan, Player initiator) {
        for (BanWaveCandidate candidate : plan.candidates()) {
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("player", candidate.player().name());
            placeholders.put("uuid", candidate.player().id().toString());
            placeholders.put("strength", candidate.peakStrength().label());
            placeholders.put("samples", String.valueOf(candidate.sampleSize()));
            placeholders.put("signals", String.valueOf(candidate.independentGroups()));
            placeholders.put("confidence", MessageService.formatProbability(candidate.peakConfidence()));
            placeholders.put("moderator", initiator == null ? "automatic" : initiator.getName());
            placeholders.put("action", "BAN");

            String reason = banReason(placeholders);
            placeholders.put("reason", reason);
            ban(candidate.player().id(), candidate.player().name(), reason, true, placeholders);
            recordModeratorAction(candidate.player().id(),
                    initiator == null ? null : initiator.getUniqueId(),
                    "BAN_WAVE", reason);

            discord.notify(AlertEvent.BAN_WAVE, candidate.peakStrength(), candidate.peakConfidence(),
                    messages.get("alerts.discord.wave-entry", placeholders));

            LOGGER.info("Ban wave removed {} — {} (confidence {}, {} observation(s), {} signal(s))",
                    candidate.player().name(), candidate.peakStrength().label(),
                    MessageService.formatProbability(candidate.peakConfidence()),
                    candidate.sampleSize(), candidate.independentGroups());
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("count", String.valueOf(plan.size()));
        summary.put("moderator", initiator == null ? "automatic" : initiator.getName());
        Bukkit.broadcastMessage(messages.get("enforcement.broadcast", summary));
        discord.notifyIfEnabled(AlertEvent.BAN_WAVE,
                messages.get("alerts.discord.wave-summary", summary));
    }

    /**
     * Removes a player, either with the configured command or by writing to the server's ban list.
     *
     * <p>When a command is configured the player is still disconnected afterwards if they are somehow
     * still online. That is not belt-and-braces for its own sake: the configured command might be a
     * temporary ban, a network-wide ban issued to a proxied server, or a command that failed quietly
     * because a plugin changed its syntax. In all three cases the caller asked for a ban, and leaving
     * the player connected would mean the evidence threshold was crossed and nothing observable
     * happened.
     */
    private void ban(UUID playerId, String playerName, String reason, boolean wave,
                     Map<String, String> placeholders) {
        CommandTemplate template = commands.banTemplate(wave);
        if (template.isConfigured()) {
            String command = template.render(commandValues(placeholders));
            dispatch(command, wave ? "ban-wave" : "ban");
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                online.kickPlayer(messages.get("actions.target-banned", Map.of("reason", reason)));
            }
            return;
        }
        applyBan(playerId, playerName, reason);
    }

    /** Removes a player, either with the configured command or with the plugin's own kick. */
    private void kick(Player target, String reason, Map<String, String> placeholders) {
        CommandTemplate template = commands.kick();
        if (template.isConfigured()) {
            dispatch(template.render(commandValues(placeholders)), "kick");
        }
        if (target.isOnline()) {
            target.kickPlayer(messages.get("actions.target-kicked", placeholders));
        }
    }

    /**
     * Runs a configured command as the console.
     *
     * <p>A command the server does not recognise is logged rather than thrown: the administrator has a
     * typo, and the alternative — an exception escaping into the scheduler — would abandon the rest of
     * the enforcement pass.
     */
    private void dispatch(String command, String kind) {
        if (command.isBlank()) {
            return;
        }
        if (commands.logCommands()) {
            LOGGER.info("Running the configured {} command: {}", kind, command);
        }
        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                LOGGER.warn("The configured {} command was not recognised by the server, so it did "
                        + "nothing: '{}'. Check enforcement.commands in config.yml.", kind, command);
            }
        } catch (RuntimeException e) {
            LOGGER.error("The configured {} command '{}' threw an exception", kind, command, e);
        }
    }

    private void applyBan(UUID playerId, String playerName, String reason) {
        Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, null, "XRayAntiCheat");
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            Map<String, String> placeholders = Map.of("reason", reason);
            online.kickPlayer(messages.get("actions.target-banned", placeholders));
        }
    }

    /** The evidence placeholders shared by the ban reason, the alert and the notifications. */
    private Map<String, String> evidencePlaceholders(SuspicionSnapshot snapshot) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", snapshot.player().name());
        placeholders.put("uuid", snapshot.player().id().toString());
        placeholders.put("world", snapshot.world().key());
        placeholders.put("strength", snapshot.evidenceStrength().label());
        placeholders.put("samples", String.valueOf(snapshot.sampleSize()));
        placeholders.put("signals", String.valueOf(snapshot.independentGroups()));
        placeholders.put("confidence", MessageService.formatProbability(snapshot.statisticalConfidence()));
        placeholders.put("score", MessageService.formatProbability(snapshot.suspicionScore()));
        placeholders.put("decibans", String.format(java.util.Locale.ROOT, "%.1f",
                LogOdds.toDecibans(snapshot.posteriorLogOdds())));
        placeholders.put("snapshot-id",
                snapshot.evaluatedAt().toEpochMilli() + "-" + snapshot.sampleSize());
        placeholders.put("action", "NONE");
        return placeholders;
    }

    /**
     * The ban reason, with the evidence identifier appended.
     *
     * <p>{@code enforcement.ban-reason-append} was defined in the messages file but never used, so the
     * reason a player was removed could not be used to find the assessment behind it. It is appended
     * here, and an administrator who does not want it can set the key to an empty string.
     */
    private String banReason(Map<String, String> placeholders) {
        return messages.get("enforcement.ban-reason", placeholders)
                + messages.get("enforcement.ban-reason-append", placeholders);
    }

    /** The subset of placeholders the command templates may reference. */
    private Map<String, String> commandValues(Map<String, String> placeholders) {
        Map<String, String> values = new LinkedHashMap<>(placeholders);
        values.putIfAbsent("moderator", "console");
        return values;
    }

    /**
     * Records a moderator's action in the audit table.
     *
     * <p>Called from the server thread, so the write itself is handed to a worker: a ban that
     * succeeds but whose audit row blocks the tick loop for a database round trip would be a poor
     * trade. The action has already happened by the time this is invoked, so a failed write is logged
     * rather than retried — the server's own ban list holds the authoritative record, and this table
     * is the explanation attached to it.
     */
    public void recordModeratorAction(UUID playerId, UUID moderatorId, String action, String note) {
        if (!persistence.isAvailable()) {
            return;
        }
        Instant performedAt = Instant.now();
        backgroundTask.accept(() -> {
            try {
                persistence.moderatorActions().record(playerId, moderatorId, action, note, performedAt);
            } catch (PersistenceException e) {
                LOGGER.error("Could not write the '{}' audit entry for player {}", action, playerId, e);
            }
        });
    }
}
