package io.xrayac.paper.enforcement;

import io.xrayac.core.decision.ActionType;
import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.decision.BanWavePlan;
import io.xrayac.core.decision.DecisionOutcome;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.repository.PersistenceException;
import io.xrayac.paper.message.MessageService;
import io.xrayac.paper.persistence.PersistenceBundle;
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
 * <h2>Server-thread confined</h2>
 * Every method here is called on the Minecraft server thread — from the scheduler for automated
 * enforcement, or directly from a command or GUI for a moderator's action. Persisting the moderator's
 * record is the exception: that is handed to a worker, because it is a database write.
 */
public final class EnforcementService
        implements java.util.function.BiConsumer<SuspicionSnapshot, DecisionOutcome> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnforcementService.class);

    private final Plugin plugin;
    private final MessageService messages;
    private final PersistenceBundle persistence;
    private final Consumer<Runnable> backgroundTask;

    public EnforcementService(Plugin plugin, MessageService messages, PersistenceBundle persistence,
                              Consumer<Runnable> backgroundTask) {
        this.plugin = plugin;
        this.messages = messages;
        this.persistence = persistence;
        this.backgroundTask = backgroundTask;
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

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", snapshot.player().name());
        placeholders.put("strength", snapshot.evidenceStrength().label());
        placeholders.put("samples", String.valueOf(snapshot.sampleSize()));
        placeholders.put("signals", String.valueOf(snapshot.independentGroups()));
        placeholders.put("confidence", MessageService.formatProbability(snapshot.statisticalConfidence()));
        placeholders.put("snapshot-id",
                snapshot.evaluatedAt().toEpochMilli() + "-" + snapshot.sampleSize());

        if (outcome.action() == ActionType.BAN) {
            String reason = messages.get("enforcement.ban-reason", placeholders);
            applyBan(snapshot.player().id(), snapshot.player().name(), reason);
            recordModeratorAction(snapshot.player().id(), null, "AUTOMATED_BAN", snapshot.explain());
            LOGGER.warn("Automatically banned {} — {} evidence, {} observation(s), {} independent "
                            + "signal(s), confidence {}", snapshot.player().name(),
                    snapshot.evidenceStrength().label(), snapshot.sampleSize(),
                    snapshot.independentGroups(),
                    MessageService.formatProbability(snapshot.statisticalConfidence()));
            return;
        }

        target.kickPlayer(messages.get("enforcement.kick-reason", placeholders));
        recordModeratorAction(snapshot.player().id(), null, "AUTOMATED_KICK", snapshot.explain());
        LOGGER.info("Automatically disconnected {} on {} evidence ({} observation(s), {} signal(s))",
                snapshot.player().name(), snapshot.evidenceStrength().label(),
                snapshot.sampleSize(), snapshot.independentGroups());
    }

    /** A moderator kicks a player, from a command or the GUI. */
    public void kick(Player moderator, Player target, String reason) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", target.getName());
        placeholders.put("moderator", moderator.getName());
        placeholders.put("reason", reason);

        target.kickPlayer(messages.get("actions.target-kicked", placeholders));
        moderator.sendMessage(messages.get("actions.kick-manual", placeholders));
        recordModeratorAction(target.getUniqueId(), moderator.getUniqueId(), "KICK", reason);
    }

    /** A moderator bans a player, from a command or the GUI. */
    public void ban(Player moderator, OfflinePlayer target, String reason) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", target.getName() == null ? target.getUniqueId().toString() : target.getName());
        placeholders.put("moderator", moderator.getName());
        placeholders.put("reason", reason);

        applyBan(target.getUniqueId(),
                target.getName() == null ? target.getUniqueId().toString() : target.getName(),
                reason);
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
            placeholders.put("strength", candidate.peakStrength().label());
            placeholders.put("samples", String.valueOf(candidate.sampleSize()));
            placeholders.put("signals", String.valueOf(candidate.independentGroups()));
            placeholders.put("confidence", MessageService.formatProbability(candidate.peakConfidence()));

            String reason = messages.get("enforcement.ban-reason", placeholders);
            applyBan(candidate.player().id(), candidate.player().name(), reason);
            recordModeratorAction(candidate.player().id(),
                    initiator == null ? null : initiator.getUniqueId(),
                    "BAN_WAVE", reason);

            LOGGER.info("Ban wave removed {} — {} (confidence {}, {} observation(s), {} signal(s))",
                    candidate.player().name(), candidate.peakStrength().label(),
                    MessageService.formatProbability(candidate.peakConfidence()),
                    candidate.sampleSize(), candidate.independentGroups());
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("count", String.valueOf(plan.size()));
        summary.put("moderator", initiator == null ? "automatic" : initiator.getName());
        Bukkit.broadcastMessage(messages.get("enforcement.broadcast", summary));
    }

    private void applyBan(UUID playerId, String playerName, String reason) {
        Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, null, "XRayAntiCheat");
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            Map<String, String> placeholders = Map.of("reason", reason);
            online.kickPlayer(messages.get("actions.target-banned", placeholders));
        }
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
