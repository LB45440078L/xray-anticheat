package io.xrayac.spigot.alert;

import io.xrayac.core.decision.DecisionOutcome;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.statistics.LogOdds;
import io.xrayac.spigot.message.MessageService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Delivers evidence alerts to staff.
 *
 * <h2>Alerts carry evidence, never conclusions</h2>
 * An alert that says "Steve is probably cheating" is unusable: a moderator cannot act on it, cannot
 * check it, and cannot disagree with it. Every alert therefore quotes the numbers it rests on — the
 * verdict band, the score, the confidence, how many independent signals agreed, and the strongest
 * single contributing signal with its own explanation sentence. A moderator can read the alert and
 * decide whether the reasoning is sound, which is the whole point of building the engine the way it
 * is built.
 *
 * <h2>Throttling</h2>
 * A player who trips the threshold will trip it again on every analysis pass, and an unthrottled
 * alert would drown the staff channel in a minute. Alerts for a given player are therefore rate
 * limited, with the suppressed count reported on the next alert so that the flood is not merely
 * hidden — a moderator still learns that forty assessments were made, not one.
 *
 * <p>Server-thread confined: it reads the online-player list and sends chat messages.
 */
public final class AlertService {

    private final Plugin plugin;
    private final MessageService messages;
    private final Duration throttle;

    private final Map<UUID, Instant> lastAlertAt = new HashMap<>();
    private final Map<UUID, Integer> suppressedSinceLastAlert = new HashMap<>();

    public AlertService(Plugin plugin, MessageService messages, Duration throttle) {
        this.plugin = plugin;
        this.messages = messages;
        this.throttle = throttle;
    }

    /**
     * Sends an alert about a player, unless one was sent recently.
     *
     * @return true when an alert was actually delivered
     */
    public boolean alert(SuspicionSnapshot snapshot, DecisionOutcome outcome) {
        UUID playerId = snapshot.player().id();
        Instant now = Instant.now();

        Instant previous = lastAlertAt.get(playerId);
        if (previous != null && Duration.between(previous, now).compareTo(throttle) < 0) {
            suppressedSinceLastAlert.merge(playerId, 1, Integer::sum);
            return false;
        }

        int suppressed = suppressedSinceLastAlert.getOrDefault(playerId, 0);
        suppressedSinceLastAlert.put(playerId, 0);
        lastAlertAt.put(playerId, now);

        Map<String, String> placeholders = buildPlaceholders(snapshot, outcome);

        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!recipient.hasPermission("xray.alerts")) {
                continue;
            }
            messages.send(recipient, "alerts.header", placeholders);
            messages.sendList(recipient, "alerts.body", placeholders);
            if (suppressed > 0) {
                Map<String, String> suppression = new LinkedHashMap<>(placeholders);
                suppression.put("count", String.valueOf(suppressed));
                suppression.put("time", MessageService.formatDuration(throttle));
                messages.send(recipient, "alerts.suppressed", suppression);
            }
            messages.send(recipient, "alerts.hint", placeholders);
            messages.send(recipient, "alerts.footer", placeholders);
        }
        return true;
    }

    /**
     * Builds the placeholder map for an alert.
     *
     * <p>The "top signal" is the contributing component with the largest absolute weighted
     * log-likelihood ratio, and it is quoted with its own explanation. Naming the strongest signal and
     * saying what it means is what turns an alert from a score into an argument.
     */
    private Map<String, String> buildPlaceholders(SuspicionSnapshot snapshot, DecisionOutcome outcome) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", snapshot.player().name());
        placeholders.put("uuid", snapshot.player().id().toString());
        placeholders.put("world", snapshot.world().key());
        placeholders.put("score", MessageService.formatProbability(snapshot.suspicionScore()));
        placeholders.put("confidence", MessageService.formatProbability(snapshot.statisticalConfidence()));
        placeholders.put("strength", snapshot.evidenceStrength().label());
        placeholders.put("samples", String.valueOf(snapshot.sampleSize()));
        placeholders.put("signals", String.valueOf(snapshot.independentGroups()));
        placeholders.put("decibans", String.format(java.util.Locale.ROOT, "%.1f",
                LogOdds.toDecibans(snapshot.posteriorLogOdds())));
        placeholders.put("evidence", snapshot.explain());
        placeholders.put("time", Instant.now().toString());
        placeholders.put("action", outcome.action().name());
        placeholders.put("justified", outcome.justifiedAction().name());

        List<EvidenceContribution> top = snapshot.contributionsByImpact();
        placeholders.put("top-signal", top.isEmpty()
                ? "none"
                : top.getFirst().componentId() + " — " + top.getFirst().explanation());

        return placeholders;
    }

    /** Forgets throttle state for a player who has disconnected. */
    public void forget(UUID playerId) {
        lastAlertAt.remove(playerId);
        suppressedSinceLastAlert.remove(playerId);
    }

    /** How many players currently have a delivered alert outstanding, for status reporting. */
    public int trackedAlertCount() {
        return lastAlertAt.size();
    }

    /** The plugin this service belongs to, exposed so callers can schedule onto the server thread. */
    public Plugin plugin() {
        return plugin;
    }
}
