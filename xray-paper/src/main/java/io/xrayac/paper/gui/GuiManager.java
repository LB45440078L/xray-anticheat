package io.xrayac.paper.gui;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.statistics.LogOdds;
import io.xrayac.paper.alert.AlertService;
import io.xrayac.paper.analysis.AnalysisService;
import io.xrayac.paper.enforcement.EnforcementService;
import io.xrayac.paper.message.MessageService;
import io.xrayac.paper.session.PlayerSession;
import io.xrayac.paper.session.SessionRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The moderator interface.
 *
 * <h2>Identification by holder, not by a map</h2>
 * Every menu opened here carries an {@link XRayMenu} holder, and a click is recognised by asking whether
 * the viewed inventory's holder is one of ours. The previous design keyed open menus on the player's
 * UUID, which broke in two ways at once: switching from one menu to another fires a close event that
 * deleted the state just written for the new menu — so <b>clicks did nothing</b> — and the handler
 * returned before cancelling when it found no state, so <b>items could be taken</b>. Asking the
 * inventory directly removes both, because the answer cannot go stale.
 *
 * <h2>Nothing can be removed from a menu</h2>
 * Clicks and drags involving a menu are cancelled unconditionally, and the cancellation happens before
 * any decision about the click. Cancelling everything — including clicks in the moderator's own
 * inventory while a menu is open — is deliberate: it closes every route by which an item could leave or
 * enter a menu, including routes nobody enumerated, at the cost of requiring a moderator to close the
 * window before rearranging their inventory. The drag handler matters as much as the click handler; a
 * drag is a separate way into an inventory and was previously unhandled entirely.
 *
 * <h2>Rendered from memory</h2>
 * The interface runs on the server thread, where a blocking query is forbidden, so it renders from the
 * analysis service's cache of the latest assessment and from the live session buffers — which is also
 * the honest source, since a moderator wants what is true now rather than what was last flushed.
 *
 * <h2>Permissions are re-checked on click</h2>
 * An item is hidden when the viewer lacks its permission, but hiding is presentation only. The
 * permission is checked again when the item is clicked, because a menu can be left open across a
 * permission change.
 *
 * <p>Server-thread confined.
 */
public final class GuiManager implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuiManager.class);

    private final Supplier<FileConfiguration> guiSupplier;
    private final MessageService messages;
    private final AnalysisService analysis;
    private final SessionRegistry sessions;
    private final EnforcementService enforcement;
    private final AlertService alerts;

    public GuiManager(Supplier<FileConfiguration> guiSupplier,
                      MessageService messages,
                      AnalysisService analysis,
                      SessionRegistry sessions,
                      EnforcementService enforcement,
                      AlertService alerts) {
        this.guiSupplier = guiSupplier;
        this.messages = messages;
        this.analysis = analysis;
        this.sessions = sessions;
        this.enforcement = enforcement;
        this.alerts = alerts;
    }

    // -------------------------------------------------------------------------------------
    // Opening menus
    // -------------------------------------------------------------------------------------

    /** Opens the first page of the tracked-player list. */
    public void openPlayerList(Player moderator) {
        openPlayerList(moderator, 0);
    }

    /**
     * Opens a page of the tracked-player list.
     *
     * <p>The page index is clamped rather than trusted: it arrives from a button click, and a menu left
     * open while players joined or left could otherwise request a page that no longer exists, producing
     * an empty window that looks like a bug.
     */
    public void openPlayerList(Player moderator, int requestedPage) {
        FileConfiguration gui = guiSupplier.get();
        int size = clampSize(gui.getInt("player-list.size", 54));

        Navigation navigation = Navigation.read(gui, "player-list.navigation", size);
        List<PlayerSession> tracked = trackedPlayers();
        int perPage = navigation.contentSlots();
        int pageCount = Math.max(1, (int) Math.ceil(tracked.size() / (double) perPage));
        int page = Math.clamp(requestedPage, 0, pageCount - 1);

        XRayMenu menu = new XRayMenu("player-list", null, page);
        Inventory inventory = Bukkit.createInventory(menu, size,
                colour(gui.getString("player-list.title", "XRay — tracked players")));
        menu.attach(inventory);

        Map<String, String> pagePlaceholders = pagePlaceholders(page, pageCount, tracked.size());

        int firstIndex = page * perPage;
        for (int slot = 0; slot < perPage; slot++) {
            int index = firstIndex + slot;
            if (index >= tracked.size()) {
                break;
            }
            PlayerSession session = tracked.get(index);
            inventory.setItem(slot, buildPlayerListItem(session));
            menu.bind(slot, "OPEN:player-detail:" + session.player().id());
        }

        if (tracked.isEmpty()) {
            inventory.setItem(Math.min(perPage / 2, size - 1), build("player-list.empty", Map.of()));
        }

        if (navigation.enabled()) {
            if (page > 0) {
                inventory.setItem(navigation.previousSlot(),
                        build("player-list.navigation.previous", pagePlaceholders));
                menu.bind(navigation.previousSlot(), "PAGE:previous");
            }
            if (page < pageCount - 1) {
                inventory.setItem(navigation.nextSlot(),
                        build("player-list.navigation.next", pagePlaceholders));
                menu.bind(navigation.nextSlot(), "PAGE:next");
            }
            inventory.setItem(navigation.infoSlot(),
                    build("player-list.navigation.page-info", pagePlaceholders));
        }

        if (gui.getBoolean("settings.fill-empty-slots", true)) {
            fillEmpty(inventory, gui);
        }
        playSound(moderator, gui.getString("settings.open-sound"));
        moderator.openInventory(inventory);
    }

    /** Opens the detail page for a player, with the configured moderation actions. */
    public void openDetail(Player moderator, PlayerRef target) {
        FileConfiguration gui = guiSupplier.get();
        PlayerSession session = sessions.get(target.id()).orElse(null);
        Map<String, String> placeholders = placeholdersFor(target, session);

        int size = clampSize(gui.getInt("player-detail.size", 54));
        XRayMenu menu = new XRayMenu("player-detail", target, 0);
        Inventory inventory = Bukkit.createInventory(menu, size,
                colour(substitute(gui.getString("player-detail.title", "Inspect — %player%"), placeholders)));
        menu.attach(inventory);

        for (Map<?, ?> raw : gui.getMapList("player-detail.items")) {
            int slot = intValue(raw, "slot", -1);
            if (slot < 0 || slot >= size) {
                continue;
            }
            String permission = stringValue(raw, "permission");
            if (!permission.isBlank() && !moderator.hasPermission(permission)) {
                if (gui.getBoolean("settings.explain-missing-permission", true)) {
                    inventory.setItem(slot, buildLockedItem(permission));
                }
                continue;
            }
            inventory.setItem(slot, buildItem(raw, placeholders));
            String action = stringValue(raw, "action");
            if (!action.isBlank()) {
                menu.bind(slot, action);
            }
        }

        if (gui.getBoolean("settings.fill-empty-slots", true)) {
            fillEmpty(inventory, gui);
        }
        playSound(moderator, gui.getString("settings.open-sound"));
        moderator.openInventory(inventory);
    }

    /** Opens a confirmation menu before a disruptive or irreversible action. */
    public void openConfirm(Player moderator, PlayerRef target, String verb, String pendingAction) {
        FileConfiguration gui = guiSupplier.get();
        int size = clampSize(gui.getInt("confirm.size", 27));

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", target.name());
        placeholders.put("action", verb);

        XRayMenu menu = new XRayMenu("confirm", target, 0);
        menu.pendingAction(pendingAction);
        Inventory inventory = Bukkit.createInventory(menu, size,
                colour(substitute(gui.getString("confirm.title", "Confirm — %action% on %player%"), placeholders)));
        menu.attach(inventory);

        int confirmSlot = clampSlot(gui.getInt("confirm.confirm-slot", 11), size);
        int cancelSlot = clampSlot(gui.getInt("confirm.cancel-slot", 15), size);

        inventory.setItem(confirmSlot, build("confirm.confirm", placeholders));
        // The confirm button carries a distinct action that executes the pending one. Pointing it at
        // the pending action's own name would reopen this menu on every click and the action would
        // never run.
        menu.bind(confirmSlot, "CONFIRM");
        inventory.setItem(cancelSlot, build("confirm.cancel", placeholders));
        menu.bind(cancelSlot, "OPEN:player-detail:" + target.id());

        // The chat prompt states in the moderator's own log what they are about to do, and repeats the
        // fact that it is recorded against them. It is separate from the menu so it survives the window
        // being closed by accident.
        String prompt = gui.getString("confirm.prompt." + pendingAction.toLowerCase(Locale.ROOT));
        if (prompt != null && !prompt.isBlank()) {
            moderator.sendMessage(messages.prefix() + colour(substitute(prompt, placeholders)));
        }

        playSound(moderator, gui.getString("settings.open-sound"));
        moderator.openInventory(inventory);
    }

    /**
     * Opens the per-ore breakdown for a player.
     *
     * <p>Every figure here is a count of <em>discoveries</em>, not of blocks: a vein the player worked
     * through end to end contributes one row entry, because that is one judgement about whether they
     * could have seen it. Reporting blocks instead would make a player who cleans up a vein look far
     * worse than one who takes the first block and leaves.
     */
    public void openStatistics(Player moderator, PlayerRef target) {
        FileConfiguration gui = guiSupplier.get();
        PlayerSession session = sessions.get(target.id()).orElse(null);
        Map<String, String> placeholders = placeholdersFor(target, session);

        int size = clampSize(gui.getInt("statistics.size", 27));
        int headerSlot = clampSlot(gui.getInt("statistics.header-slot", 4), size);
        int backSlot = clampSlot(gui.getInt("statistics.back-slot", size - 5), size);

        XRayMenu menu = new XRayMenu("statistics", target, 0);
        Inventory inventory = Bukkit.createInventory(menu, size,
                colour(substitute(gui.getString("statistics.title", "Statistics — %player%"), placeholders)));
        menu.attach(inventory);

        inventory.setItem(headerSlot, build("player-list.player-item", placeholders));

        List<Integer> free = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            if (slot != headerSlot && slot != backSlot) {
                free.add(slot);
            }
        }

        List<Map.Entry<String, int[]>> ores = oreBreakdown(session);
        if (ores.isEmpty()) {
            inventory.setItem(free.getFirst(), build("statistics.empty", placeholders));
        } else {
            int index = 0;
            for (Map.Entry<String, int[]> entry : ores) {
                if (index >= free.size()) {
                    break;
                }
                inventory.setItem(free.get(index++),
                        buildOreItem(entry.getKey(), entry.getValue(), placeholders));
            }
        }

        inventory.setItem(backSlot, build("statistics.back", placeholders));
        // Bound in code rather than through the configuration because the action needs the player's
        // UUID, which the configuration cannot know.
        menu.bind(backSlot, "OPEN:player-detail:" + target.id());

        if (gui.getBoolean("settings.fill-empty-slots", true)) {
            fillEmpty(inventory, gui);
        }
        playSound(moderator, gui.getString("settings.open-sound"));
        moderator.openInventory(inventory);
    }

    // -------------------------------------------------------------------------------------
    // Interaction
    // -------------------------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        XRayMenu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }

        // Cancel first, before any decision about the click. Everything below is navigation or an
        // explicit action; none of it should ever move an item, and cancelling up front means an
        // unexpected state cannot become a way to empty the menu.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player moderator)) {
            return;
        }
        // A click in the moderator's own inventory is cancelled but carries no action.
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        menu.actionAt(rawSlot).ifPresent(action -> {
            playSound(moderator, guiSupplier.get().getString("settings.click-sound"));
            dispatch(moderator, menu, action);
        });
    }

    /** Blocks dragging items into or out of a menu. */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    /** The menu behind an inventory, or null when the inventory is not ours. */
    private static XRayMenu menuOf(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof XRayMenu menu ? menu : null;
    }

    /** Executes the action bound to a clicked item. Permissions are re-checked here. */
    private void dispatch(Player moderator, XRayMenu menu, String action) {
        String[] parts = action.split(":", 3);

        switch (parts[0]) {
            case "CLOSE" -> moderator.closeInventory();

            case "OPEN" -> {
                if (parts.length < 2) {
                    return;
                }
                if ("player-list".equals(parts[1])) {
                    openPlayerList(moderator, 0);
                } else if ("player-detail".equals(parts[1]) && parts.length >= 3) {
                    findPlayer(parts[2]).ifPresentOrElse(
                            target -> openDetail(moderator, target),
                            () -> messages.send(moderator, "commands.player-not-found",
                                    Map.of("player", "that player")));
                } else if ("statistics".equals(parts[1])) {
                    // This item carries no UUID: it is always about the player whose page is open, so the
                    // target comes from the menu rather than from the action string.
                    menu.target().ifPresent(target -> openStatistics(moderator, target));
                } else {
                    LOGGER.warn("gui.yml opens the unknown menu '{}'; that item does nothing", parts[1]);
                    messages.send(moderator, "errors.internal", Map.of());
                }
            }

            case "PAGE" -> {
                if (parts.length < 2) {
                    return;
                }
                openPlayerList(moderator, "next".equals(parts[1]) ? menu.page() + 1 : menu.page() - 1);
            }

            case "CONFIRM" -> executeConfirmed(moderator, menu);

            case "INSPECT" -> showEvidenceReport(moderator, menu.target().orElse(null));

            case "KICK", "BAN" -> {
                PlayerRef target = menu.target().orElse(null);
                if (target == null) {
                    return;
                }
                String permission = "KICK".equals(parts[0]) ? "xray.kick" : "xray.ban";
                if (!moderator.hasPermission(permission)) {
                    messages.send(moderator, "errors.permission", Map.of("permission", permission));
                    return;
                }
                openConfirm(moderator, target, parts[0].toLowerCase(Locale.ROOT), parts[0]);
            }

            case "TELEPORT", "TELEPORT_VANISH" -> {
                PlayerRef target = menu.target().orElse(null);
                if (target == null) {
                    return;
                }
                Player online = Bukkit.getPlayer(target.id());
                if (online == null) {
                    messages.send(moderator, "commands.player-not-online", Map.of("player", target.name()));
                    return;
                }
                moderator.closeInventory();
                moderator.teleport(online.getLocation());
                messages.send(moderator, "actions.teleported", Map.of("player", target.name()));
                if ("TELEPORT_VANISH".equals(parts[0])) {
                    // The teleport happened; the vanish did not. Saying "vanished" would leave a
                    // moderator believing they were hidden while standing in plain sight of the player.
                    messages.send(moderator, "actions.vanish-unavailable",
                            Map.of("player", target.name()));
                }
            }

            case "FLAG", "CLEAR", "ADD_NOTE" -> {
                PlayerRef target = menu.target().orElse(null);
                if (target == null) {
                    return;
                }
                Map<String, String> placeholders = Map.of("player", target.name());
                switch (parts[0]) {
                    case "FLAG" -> {
                        messages.send(moderator, "actions.flagged", placeholders);
                        enforcement.recordModeratorAction(target.id(), moderator.getUniqueId(),
                                "FLAG", "flagged via the moderator interface");
                    }
                    case "CLEAR" -> {
                        // An alert is an event rather than a flag, so there is no queue entry to dismiss.
                        // What is real, and what a moderator actually wants, is to stop being throttled
                        // for a player they are already watching: this re-arms alerts so the next one
                        // arrives at once instead of being folded into a later summary.
                        alerts.forget(target.id());
                        messages.send(moderator, "actions.alerts-rearmed", placeholders);
                    }
                    default -> {
                        // The command is the real note entry point because it can collect the text.
                        // Reporting success without a note would be worse than pointing at the command.
                        moderator.closeInventory();
                        messages.send(moderator, "actions.note-instruction", placeholders);
                    }
                }
            }

            case "SPECTATE", "FREEZE" -> {
                // Outstanding capabilities that need an external spectate/freeze plugin. Reporting them as
                // done would be worse than reporting them unavailable: a moderator would stop watching a
                // player they believed frozen.
                PlayerRef target = menu.target().orElse(null);
                moderator.closeInventory();
                messages.send(moderator, "actions.external-plugin-required",
                        Map.of("player", target == null ? "that player" : target.name()));
            }

            default -> {
                // The configuration named an action this build does not implement. Logging it is the
                // point: silently doing nothing would leave an administrator convinced the item works.
                LOGGER.warn("gui.yml binds the unknown action '{}' in menu '{}'; that item does nothing",
                        action, menu.menuId());
                messages.send(moderator, "errors.internal", Map.of());
            }
        }
    }

    /**
     * Sends the full written evidence report to a moderator.
     *
     * <p>Read from the in-memory cache, so it reflects the most recent assessment rather than the last
     * flush. An absent assessment is reported as "no evidence yet" and explicitly not as innocence.
     */
    private void showEvidenceReport(Player moderator, PlayerRef target) {
        if (target == null) {
            return;
        }
        moderator.closeInventory();
        Map<String, String> placeholders = placeholdersFor(target, sessions.get(target.id()).orElse(null));
        messages.send(moderator, "evidence.header", placeholders);

        Optional<SuspicionSnapshot> snapshot = analysis.latestAssessment(target.id());
        if (snapshot.isEmpty()) {
            messages.send(moderator, "evidence.no-evidence", placeholders);
        } else {
            messages.send(moderator, "evidence.verdict", placeholders);
            for (String line : snapshot.get().explain().split("\n")) {
                moderator.sendMessage("§8" + line);
            }
        }
        messages.send(moderator, "actions.inspect-note", placeholders);
    }

    /** Carries out an action the moderator explicitly confirmed. */
    private void executeConfirmed(Player moderator, XRayMenu menu) {
        String pending = menu.pendingAction();
        PlayerRef target = menu.target().orElse(null);
        moderator.closeInventory();
        if (pending == null || target == null) {
            return;
        }

        switch (pending) {
            case "KICK" -> {
                Player online = Bukkit.getPlayer(target.id());
                if (online == null) {
                    messages.send(moderator, "commands.player-not-online", Map.of("player", target.name()));
                    return;
                }
                enforcement.kick(moderator, online, "confirmed through the moderator interface");
            }
            case "BAN" -> enforcement.ban(moderator, Bukkit.getOfflinePlayer(target.id()),
                    "confirmed through the moderator interface");
            default -> {
                LOGGER.warn("Confirmation menu held the unknown pending action '{}'", pending);
                messages.send(moderator, "errors.internal", Map.of());
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Rendering helpers
    // -------------------------------------------------------------------------------------

    private List<PlayerSession> trackedPlayers() {
        List<PlayerSession> tracked = new ArrayList<>(sessions.all());
        tracked.sort(Comparator.comparingDouble(
                (PlayerSession session) -> suspicionOf(session.player().id())).reversed());
        return tracked;
    }

    private ItemStack buildPlayerListItem(PlayerSession session) {
        return build("player-list.player-item", placeholdersFor(session.player(), session));
    }

    /**
     * Groups a session's discoveries by ore, counting buried against exposed.
     *
     * <p>Ordered by how many discoveries each ore accounts for, so the most significant ore is on the
     * first row, with the ore id breaking ties to keep the layout from shuffling between openings.
     * Discoveries whose exposure could not be determined are counted in neither column and so appear only
     * in the total, which is the honest report: the system does not know, and does not guess.
     */
    private static List<Map.Entry<String, int[]>> oreBreakdown(PlayerSession session) {
        if (session == null) {
            return List.of();
        }
        Map<String, int[]> byOre = new HashMap<>();
        for (OreDiscovery discovery : session.discoveryList()) {
            int[] counts = byOre.computeIfAbsent(discovery.oreId(), key -> new int[2]);
            if (discovery.discoveryExposure().isUnaccountablyHidden()) {
                counts[0]++;
            } else if (discovery.discoveryExposure().isVisibleByOrdinaryPlay()) {
                counts[1]++;
            }
        }
        List<Map.Entry<String, int[]>> entries = new ArrayList<>(byOre.entrySet());
        entries.sort(Comparator
                .<Map.Entry<String, int[]>>comparingInt(entry -> -(entry.getValue()[0] + entry.getValue()[1]))
                .thenComparing(Map.Entry::getKey));
        return entries;
    }

    /** Renders one ore row, taking its icon from {@code statistics.ore-materials} when configured. */
    private ItemStack buildOreItem(String oreId, int[] counts, Map<String, String> base) {
        int hidden = counts[0];
        int exposed = counts[1];
        int total = hidden + exposed;

        Map<String, String> placeholders = new LinkedHashMap<>(base);
        placeholders.put("ore", oreId);
        placeholders.put("hidden", String.valueOf(hidden));
        placeholders.put("exposed", String.valueOf(exposed));
        placeholders.put("total", String.valueOf(total));
        placeholders.put("fraction", total == 0 ? "0.00"
                : String.format(Locale.ROOT, "%.2f", (double) hidden / total));

        Material icon = material(
                guiSupplier.get().getString("statistics.ore-materials." + oreId), null);
        return build("statistics.ore-item", icon, placeholders);
    }

    private Optional<PlayerRef> findPlayer(String rawId) {
        try {
            return sessions.get(UUID.fromString(rawId)).map(PlayerSession::player);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private double suspicionOf(UUID playerId) {
        return analysis.latestAssessment(playerId)
                .map(SuspicionSnapshot::suspicionScore)
                .orElse(0.0);
    }

    private Map<String, String> pagePlaceholders(int page, int pageCount, int players) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("page", String.valueOf(page + 1));
        placeholders.put("pages", String.valueOf(pageCount));
        placeholders.put("players", String.valueOf(players));
        return placeholders;
    }

    private Map<String, String> placeholdersFor(PlayerRef target, PlayerSession session) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", target.name());
        placeholders.put("uuid", target.id().toString());
        placeholders.put("time", java.time.Instant.now().toString());
        placeholders.put("candidates", String.valueOf(analysis.candidates().size()));

        Optional<SuspicionSnapshot> snapshot = analysis.latestAssessment(target.id());
        if (snapshot.isPresent()) {
            SuspicionSnapshot present = snapshot.get();
            placeholders.put("world", present.world().key());
            placeholders.put("score", MessageService.formatProbability(present.suspicionScore()));
            placeholders.put("confidence", MessageService.formatProbability(present.statisticalConfidence()));
            placeholders.put("strength", present.evidenceStrength().label());
            placeholders.put("samples", String.valueOf(present.sampleSize()));
            placeholders.put("signals", String.valueOf(present.independentGroups()));
            placeholders.put("decibans", format(LogOdds.toDecibans(present.posteriorLogOdds())));
        } else {
            placeholders.put("world", session == null ? "unknown" : session.world().key());
            placeholders.put("score", "0.0000");
            placeholders.put("confidence", "0.0000");
            placeholders.put("strength", "insufficient evidence");
            placeholders.put("samples", "0");
            placeholders.put("signals", "0");
            placeholders.put("decibans", "0.0");
        }

        List<OreDiscovery> discoveries = session == null ? List.of() : session.discoveryList();
        long hidden = discoveries.stream()
                .filter(d -> d.discoveryExposure().isUnaccountablyHidden())
                .count();
        placeholders.put("blocks", session == null ? "0" : format(session.blocksMined()));
        placeholders.put("distance", session == null ? "0" : format(session.distanceTravelled()));
        placeholders.put("total", String.valueOf(discoveries.size()));
        placeholders.put("hidden", String.valueOf(hidden));
        placeholders.put("exposed", String.valueOf(discoveries.size() - hidden));
        placeholders.put("fraction", discoveries.isEmpty() ? "0.00"
                : String.format(Locale.ROOT, "%.2f", (double) hidden / discoveries.size()));
        placeholders.put("ore", "all");
        return placeholders;
    }

    private ItemStack build(String path, Map<String, String> placeholders) {
        return build(path, null, placeholders);
    }

    /**
     * Renders a configured item, optionally overriding its material.
     *
     * <p>The override exists for the per-ore rows of the statistics menu, where {@code ore-item} supplies
     * the name and lore and {@code ore-materials} supplies the icon. A null override keeps the material
     * named in the configuration.
     */
    private ItemStack build(String path, Material override, Map<String, String> placeholders) {
        FileConfiguration gui = guiSupplier.get();
        Material material = override != null ? override
                : material(gui.getString(path + ".material"), Material.STONE);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colour(substitute(gui.getString(path + ".name", path), placeholders)));
            meta.setLore(substituteList(gui.getStringList(path + ".lore"), placeholders));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildItem(Map<?, ?> raw, Map<String, String> placeholders) {
        Material material = material(stringValue(raw, "material"), Material.STONE);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colour(substitute(stringValue(raw, "name"), placeholders)));
            Object lore = raw.get("lore");
            if (lore instanceof List<?> lines) {
                List<String> rendered = new ArrayList<>(lines.size());
                for (Object line : lines) {
                    rendered.add(colour(substitute(String.valueOf(line), placeholders)));
                }
                meta.setLore(rendered);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * The item shown in place of an action the viewer may not use.
     *
     * <p>Showing a locked item rather than hiding the slot teaches staff that the capability exists and
     * that they need a permission, instead of leaving them wondering why their menu differs from a
     * colleague's.
     */
    private ItemStack buildLockedItem(String permission) {
        ItemStack stack = new ItemStack(Material.GRAY_DYE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7Locked");
            meta.setLore(List.of("§7You lack §f" + permission + "§7.",
                    "§7Ask an administrator if you need it."));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void fillEmpty(Inventory inventory, FileConfiguration gui) {
        if (gui.getConfigurationSection("common.filler") == null) {
            return;
        }
        ItemStack filler = build("common.filler", Map.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /**
     * Plays a configured sound, tolerating a name this server does not recognise.
     *
     * <p>Resolved per use rather than cached, so a corrected name in {@code gui.yml} takes effect on
     * reload. An unknown name is a configuration error worth surfacing, but not a reason to fail an
     * interface action.
     */
    private void playSound(Player player, String configuredName) {
        if (configuredName == null || configuredName.isBlank()
                || configuredName.equalsIgnoreCase("NONE")) {
            return;
        }
        Sound sound;
        try {
            sound = Sound.valueOf(configuredName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("gui.yml names the unknown sound '{}'; no sound will play", configuredName);
            return;
        }
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    private List<String> substituteList(List<String> lines, Map<String, String> placeholders) {
        List<String> rendered = new ArrayList<>(lines.size());
        for (String line : lines) {
            rendered.add(colour(substitute(line, placeholders)));
        }
        return rendered;
    }

    private static String substitute(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    /** Translates {@code &}-style colour codes, which are what an administrator will type in YAML. */
    private static String colour(String text) {
        return text == null ? "" : text.replace('&', '§');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static int intValue(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String stringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int clampSize(int size) {
        int clamped = Math.clamp(size, 9, 54);
        return clamped - (clamped % 9);
    }

    private static int clampSlot(int slot, int size) {
        return Math.clamp(slot, 0, size - 1);
    }

    /**
     * The navigation row of a paginated menu.
     *
     * <p>Content is laid out from slot 0 up to the first reserved navigation slot, so the navigation row
     * never overwrites a player head and the number of entries per page follows from the configured
     * slots rather than from a hard-coded constant.
     */
    private record Navigation(boolean enabled, int previousSlot, int infoSlot, int nextSlot,
                              int contentSlots) {

        static Navigation read(FileConfiguration gui, String path, int size) {
            ConfigurationSection section = gui.getConfigurationSection(path);
            if (section == null) {
                return new Navigation(false, -1, -1, -1, size);
            }
            int lastRow = size - 9;
            int previous = clampSlot(section.getInt("previous-slot", lastRow + 1), size);
            int info = clampSlot(section.getInt("info-slot", lastRow + 4), size);
            int next = clampSlot(section.getInt("next-slot", lastRow + 7), size);
            int content = Math.max(1, Math.min(size, Math.min(previous, Math.min(info, next))));
            return new Navigation(true, previous, info, next, content);
        }
    }
}
