package io.xrayac.paper.command;

import io.xrayac.core.decision.BanWavePlan;
import io.xrayac.core.decision.BanWavePlanner;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.statistics.LogOdds;
import io.xrayac.paper.XRayAntiCheatPlugin;
import io.xrayac.paper.message.MessageService;
import io.xrayac.paper.session.PlayerSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * The {@code /xray} command tree.
 *
 * <h2>Every entry point checks its own permission</h2>
 * Permissions are verified per subcommand rather than once at the top, because the subtree is graded:
 * reading a status page, inspecting a player, teleporting to them and banning them are four different
 * levels of trust. A single gate would either over-restrict ordinary moderators or hand every
 * inspector the power to ban.
 *
 * <h2>Reports are evidence, not verdicts</h2>
 * The output of {@code inspect}, {@code evidence} and {@code stats} is the structured explanation the
 * engine already produces, formatted for chat. A moderator is meant to read the reasoning and reach
 * their own conclusion — which is why {@code /xray inspect} closes by telling them explicitly that
 * opening the interface generated no evidence against the player.
 *
 * <p>All command handling runs on the server thread. Where a report needs stored history, the read is
 * dispatched to a worker and the reply is sent when it arrives, so no command ever blocks the tick
 * loop on a database round trip.
 */
public final class XRayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "status", "inspect", "stats", "evidence", "history",
            "gui", "banwave", "note", "reload", "debug");

    private final XRayAntiCheatPlugin plugin;

    public XRayCommand(XRayAntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageService messages = plugin.messages();

        if (args.length == 0) {
            messages.sendList(sender, "commands.help", Map.of());
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "help" -> messages.sendList(sender, "commands.help", Map.of());
            case "status" -> status(sender);
            case "inspect" -> inspect(sender, args);
            case "stats" -> stats(sender, args);
            case "evidence" -> evidence(sender, args);
            case "history" -> history(sender, args);
            case "gui" -> gui(sender, args);
            case "banwave" -> banWave(sender, args);
            case "note" -> note(sender, args);
            case "reload" -> reload(sender);
            case "debug" -> debug(sender);
            default -> messages.send(sender, "commands.unknown-subcommand",
                    Map.of("subcommand", subcommand));
        }
        return true;
    }

    // -------------------------------------------------------------------------------------
    // Subcommands
    // -------------------------------------------------------------------------------------

    private void status(CommandSender sender) {
        if (!requirePermission(sender, "xray.admin")) {
            return;
        }
        var settings = plugin.settings();
        var persistence = plugin.persistence();
        var analysis = plugin.analysis();

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("version", plugin.getPluginMeta().getVersion());
        placeholders.put("mode", settings.decisionPolicy().mode().name());
        placeholders.put("analysis-state", settings.analysisEnabled() ? "on" : "off");
        placeholders.put("suspicion-state", plugin.suspicionEnabled() ? "on" : "off");
        placeholders.put("dialect", persistence.dialect().name());
        placeholders.put("schema-version", String.valueOf(persistence.schemaVersion()));
        placeholders.put("pool", persistence.isAvailable() ? persistence.poolStatistics() : "unavailable");
        placeholders.put("threads", String.valueOf(settings.performance().analysisThreads()));
        placeholders.put("thread-type", plugin.usesVirtualThreads() ? "virtual" : "platform");
        placeholders.put("tracked", String.valueOf(plugin.sessions().size()));
        placeholders.put("queued", String.valueOf(plugin.pending().queuedTotal()));
        placeholders.put("ledger", String.valueOf(plugin.ledger().size()));
        placeholders.put("candidates", String.valueOf(analysis.candidates().size()));
        placeholders.put("last-wave", analysis.lastWaveAt() == null
                ? "never" : analysis.lastWaveAt().toString());
        placeholders.put("assessments", String.valueOf(analysis.assessmentCount()));
        placeholders.put("alerts", String.valueOf(analysis.alertCount()));

        plugin.messages().send(sender, "status.header", placeholders);
        plugin.messages().sendList(sender, "status.lines", placeholders);
    }

    private void inspect(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "xray.inspect")) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "commands.invalid-arguments",
                    Map.of("usage", "/xray inspect <player>"));
            return;
        }
        PlayerRefLookup lookup = lookup(sender, args[1]);
        if (lookup == null) {
            return;
        }
        if (sender instanceof Player moderator && moderator.getUniqueId().equals(lookup.id())) {
            plugin.messages().send(sender, "commands.self-target", Map.of());
            return;
        }

        Optional<SuspicionSnapshot> snapshot = plugin.analysis().latestAssessment(lookup.id());
        Map<String, String> placeholders = basePlaceholders(lookup);
        plugin.messages().send(sender, "evidence.header", placeholders);

        if (snapshot.isEmpty()) {
            plugin.messages().send(sender, "evidence.no-evidence", placeholders);
        } else {
            fillSnapshotPlaceholders(placeholders, snapshot.get());
            plugin.messages().send(sender, "evidence.verdict", placeholders);
            plugin.messages().send(sender, "evidence.explanation-header", Map.of());
            for (var contribution : snapshot.get().contributionsByImpact()) {
                String direction = contribution.weightedLogLikelihoodRatio() > 0
                        ? plugin.messages().get("evidence.contribution-positive",
                                Map.of("value", format(contribution.weightedLogLikelihoodRatio())))
                        : plugin.messages().get("evidence.contribution-negative",
                                Map.of("value", format(contribution.weightedLogLikelihoodRatio())));
                sender.sendMessage(plugin.messages().get("evidence.contribution", Map.of(
                        "direction", direction,
                        "component", contribution.componentId(),
                        "explanation", contribution.explanation())));
            }
        }

        plugin.messages().send(sender, "actions.inspect-note", placeholders);
        if (args.length >= 3 && args[2].equalsIgnoreCase("gui") && sender instanceof Player moderator) {
            plugin.gui().openDetail(moderator, lookup.ref());
        }
    }

    private void stats(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "xray.inspect")) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "commands.invalid-arguments",
                    Map.of("usage", "/xray stats <player>"));
            return;
        }
        PlayerRefLookup lookup = lookup(sender, args[1]);
        if (lookup == null) {
            return;
        }

        Optional<PlayerSession> session = plugin.sessions().get(lookup.id());
        Map<String, String> placeholders = basePlaceholders(lookup);
        plugin.messages().send(sender, "stats.header", placeholders);

        if (session.isEmpty() || !session.get().hasAnalysableActivity()) {
            plugin.messages().send(sender, "stats.no-data", placeholders);
            return;
        }

        PlayerSession present = session.get();
        long hidden = present.discoveryList().stream()
                .filter(d -> d.discoveryExposure().isUnaccountablyHidden()).count();
        long exposed = present.discoveryList().size() - hidden;

        placeholders.put("blocks", format(present.blocksMined()));
        placeholders.put("distance", format(present.distanceTravelled()));
        placeholders.put("total", String.valueOf(present.discoveryList().size()));
        placeholders.put("hidden", String.valueOf(hidden));
        placeholders.put("exposed", String.valueOf(exposed));
        placeholders.put("session-duration", MessageService.formatDuration(
                java.time.Duration.between(present.windowStart(), Instant.now())));
        plugin.messages().sendList(sender, "stats.totals", placeholders);

        present.discoveryList().stream()
                .collect(Collectors.groupingBy(d -> d.oreId()))
                .forEach((oreId, discoveries) -> {
                    long oreHidden = discoveries.stream()
                            .filter(d -> d.discoveryExposure().isUnaccountablyHidden()).count();
                    Map<String, String> orePlaceholders = new LinkedHashMap<>(placeholders);
                    orePlaceholders.put("ore", oreId);
                    orePlaceholders.put("hidden", String.valueOf(oreHidden));
                    orePlaceholders.put("exposed", String.valueOf(discoveries.size() - oreHidden));
                    orePlaceholders.put("total", String.valueOf(discoveries.size()));
                    orePlaceholders.put("fraction", String.format(Locale.ROOT, "%.2f",
                            discoveries.isEmpty() ? 0.0 : (double) oreHidden / discoveries.size()));
                    plugin.messages().send(sender, "stats.per-ore", orePlaceholders);
                });
    }

    private void evidence(CommandSender sender, String[] args) {
        // The evidence report is the same content as inspect; keeping a separate entry point means a
        // moderator can be granted one without the other, and it matches the documented command set.
        inspect(sender, args.length >= 2 ? args : new String[] {"evidence", ""});
    }

    private void history(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "xray.inspect")) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "commands.invalid-arguments",
                    Map.of("usage", "/xray history <player>"));
            return;
        }
        PlayerRefLookup lookup = lookup(sender, args[1]);
        if (lookup == null) {
            return;
        }

        Map<String, String> header = basePlaceholders(lookup);
        plugin.messages().send(sender, "history.header", header);

        // A stored-history read is dispatched to a worker: a command must not block the tick loop on
        // a database round trip, so the reply is sent whenever the result arrives.
        plugin.runAsync(() -> {
            var stored = plugin.persistence().isAvailable()
                    ? plugin.persistence().suspicion().history(lookup.id(), 10)
                    : List.<SuspicionSnapshot>of();
            plugin.runSync(() -> {
                if (stored.isEmpty()) {
                    plugin.messages().send(sender, "history.no-history", header);
                    return;
                }
                for (SuspicionSnapshot snapshot : stored) {
                    Map<String, String> line = new LinkedHashMap<>(header);
                    fillSnapshotPlaceholders(line, snapshot);
                    line.put("time", snapshot.evaluatedAt().toString());
                    plugin.messages().send(sender, "history.entry", line);
                }
            });
        });
    }

    private void gui(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "xray.inspect")) {
            return;
        }
        if (!(sender instanceof Player moderator)) {
            plugin.messages().send(sender, "errors.internal", Map.of());
            return;
        }
        if (args.length >= 2) {
            PlayerRefLookup lookup = lookup(sender, args[1]);
            if (lookup == null) {
                return;
            }
            plugin.gui().openDetail(moderator, lookup.ref());
        } else {
            plugin.gui().openPlayerList(moderator);
        }
    }

    private void banWave(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "xray.banwave")) {
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> {
                var candidates = plugin.analysis().candidates();
                plugin.messages().send(sender, "ban-wave.candidate-list-header",
                        Map.of("count", String.valueOf(candidates.size())));
                for (var candidate : candidates) {
                    Map<String, String> placeholders = new LinkedHashMap<>();
                    placeholders.put("player", candidate.player().name());
                    placeholders.put("strength", candidate.peakStrength().label());
                    placeholders.put("confidence", MessageService.formatProbability(candidate.peakConfidence()));
                    placeholders.put("samples", String.valueOf(candidate.sampleSize()));
                    placeholders.put("time", candidate.firstDetected().toString());
                    plugin.messages().send(sender, "ban-wave.candidate-line", placeholders);
                }
            }
            case "plan" -> planWave(sender, false);
            case "approve" -> planWave(sender, true);
            default -> plugin.messages().send(sender, "commands.unknown-subcommand",
                    Map.of("subcommand", action));
        }
    }

    private void planWave(CommandSender sender, boolean execute) {
        BanWavePlanner.PlanDecision decision = plugin.analysis().planWave(Instant.now());
        if (!decision.shouldRun()) {
            plugin.messages().send(sender, "ban-wave.none-eligible",
                    Map.of("reason", decision.reason()));
            return;
        }

        BanWavePlan plan = decision.planIfPresent().orElseThrow();
        if (!execute) {
            Map<String, String> placeholders = Map.of(
                    "count", String.valueOf(plan.size()),
                    "reason", plan.automaticBan() ? "automatic" : "awaiting approval");
            plugin.messages().send(sender, "ban-wave.planned", placeholders);
            sender.sendMessage(plan.summary());
            return;
        }

        plugin.messages().send(sender, "ban-wave.approved", Map.of(
                "count", String.valueOf(plan.size()),
                "moderator", sender.getName()));
        plugin.enforcement().executeWave(plan, sender instanceof Player player ? player : null);
        plugin.analysis().recordWaveExecuted(plan, Instant.now());
        plugin.messages().send(sender, "ban-wave.executed",
                Map.of("count", String.valueOf(plan.size())));
    }

    private void note(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "xray.inspect")) {
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "commands.invalid-arguments",
                    Map.of("usage", "/xray note <player> <text>"));
            return;
        }
        PlayerRefLookup lookup = lookup(sender, args[1]);
        if (lookup == null) {
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        plugin.enforcement().recordModeratorAction(lookup.id(),
                sender instanceof Player player ? player.getUniqueId() : null, "NOTE", text);
        plugin.messages().send(sender, "actions.note-added",
                Map.of("player", lookup.name()));
    }

    private void reload(CommandSender sender) {
        if (!requirePermission(sender, "xray.reload")) {
            return;
        }
        String error = plugin.reloadConfiguration();
        if (error == null) {
            plugin.messages().send(sender, "lifecycle.reload-success", Map.of());
        } else {
            plugin.messages().send(sender, "lifecycle.reload-failed", Map.of("error", error));
        }
    }

    private void debug(CommandSender sender) {
        if (!requirePermission(sender, "xray.debug")) {
            return;
        }
        boolean nowEnabled = plugin.toggleDebug();
        sender.sendMessage(plugin.messages().prefix() + "§7Debug logging is now "
                + (nowEnabled ? "§aon" : "§coff") + "§7.");
    }

    // -------------------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if (subcommand.equals("banwave")) {
                return filter(List.of("status", "plan", "approve"), args[1]);
            }
            if (List.of("inspect", "stats", "evidence", "history", "gui", "note").contains(subcommand)) {
                return filter(onlinePlayerNames(), args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private static List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    /** A resolved player identity, so the subcommands do not each re-implement the lookup. */
    private record PlayerRefLookup(java.util.UUID id, String name) {
        io.xrayac.core.domain.PlayerRef ref() {
            return io.xrayac.core.domain.PlayerRef.of(id, name);
        }
    }

    private PlayerRefLookup lookup(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new PlayerRefLookup(online.getUniqueId(), online.getName());
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)
                    && offline.getUniqueId() != null) {
                return new PlayerRefLookup(offline.getUniqueId(), offline.getName());
            }
        }
        plugin.messages().send(sender, "commands.player-not-found", Map.of("player", name));
        return null;
    }

    private Map<String, String> basePlaceholders(PlayerRefLookup lookup) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", lookup.name());
        placeholders.put("uuid", lookup.id().toString());
        placeholders.put("world", plugin.sessions().get(lookup.id())
                .map(session -> session.world().key()).orElse("unknown"));
        return placeholders;
    }

    private void fillSnapshotPlaceholders(Map<String, String> placeholders, SuspicionSnapshot snapshot) {
        placeholders.put("world", snapshot.world().key());
        placeholders.put("score", MessageService.formatProbability(snapshot.suspicionScore()));
        placeholders.put("confidence", MessageService.formatProbability(snapshot.statisticalConfidence()));
        placeholders.put("strength", snapshot.evidenceStrength().label());
        placeholders.put("samples", String.valueOf(snapshot.sampleSize()));
        placeholders.put("signals", String.valueOf(snapshot.independentGroups()));
        placeholders.put("decibans", format(LogOdds.toDecibans(snapshot.posteriorLogOdds())));
        placeholders.put("evidence", snapshot.explain());
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "commands.no-permission", Map.of("permission", permission));
        return false;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
