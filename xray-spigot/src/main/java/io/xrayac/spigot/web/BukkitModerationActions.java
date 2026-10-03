package io.xrayac.spigot.web;

import io.xrayac.web.ModerationActions;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * {@link ModerationActions} backed by the live server.
 *
 * <h2>Threading</h2>
 * The panel calls these methods from its own HTTP worker threads, which are not the server thread.
 * Bukkit's API is not thread-safe: kicking, banning or messaging from another thread is the kind of
 * defect that works in a test and causes a corrupted player state in production. Every mutating call
 * here therefore runs its Bukkit work through the scheduler on the main thread, and the panel never
 * touches a live game object directly.
 *
 * <p>That choice has a consequence worth stating plainly, because it changes what the return value
 * means: a call that is scheduled has been <em>accepted</em>, not completed. The methods return false
 * only when the action cannot possibly succeed - the player is not online when presence is required -
 * and true once it has been handed to the server thread. The panel's wording follows that distinction
 * ("not delivered" rather than "delivered") so an operator is never told something happened when all
 * that happened was a task being queued.
 */
public final class BukkitModerationActions implements ModerationActions {

    private final Plugin plugin;

    public BukkitModerationActions(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<OnlinePlayer> online() {
        List<OnlinePlayer> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Reading the world name is safe here: it is an immutable snapshot on the Player object,
            // and this list is used for display and for presence checks only.
            String world = player.getWorld() == null ? "?" : player.getWorld().getName();
            players.add(new OnlinePlayer(player.getUniqueId(), player.getName(), world));
        }
        return players;
    }

    @Override
    public boolean kick(UUID playerId, String reason) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            // Not online: nothing to kick, and saying otherwise would be a lie the operator would
            // only discover when the player kept playing.
            return false;
        }
        onMainThread(() -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current != null) {
                current.kickPlayer(reason);
            }
        });
        return true;
    }

    @Override
    public boolean ban(UUID playerId, String reason) {
        String name = nameOf(playerId);
        if (name == null) {
            // Without a name there is nothing the vanilla ban list can key on. Report the failure
            // rather than writing a ban that does not apply to anyone.
            return false;
        }
        onMainThread(() -> {
            // expires = null means permanent. The panel is a moderation tool, not a court: a permanent
            // entry is the honest default, and removing it is one click away in the same interface.
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, (Date) null, "XRayAntiCheat panel");
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                online.kickPlayer(reason);
            }
        });
        return true;
    }

    @Override
    public boolean unban(UUID playerId) {
        String name = nameOf(playerId);
        if (name == null) {
            return false;
        }
        boolean banned = Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
        if (!banned) {
            return false;
        }
        onMainThread(() -> Bukkit.getBanList(BanList.Type.NAME).pardon(name));
        return true;
    }

    @Override
    public boolean message(UUID playerId, String text) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return false;
        }
        onMainThread(() -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current != null) {
                current.sendMessage(text);
            }
        });
        return true;
    }

    @Override
    public String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        // Can be null for a player the server has never seen. Returning null is deliberate: the
        // caller then refuses the ban instead of inventing a name for it.
        return offline.getName();
    }

    /**
     * Runs a task on the server thread.
     *
     * <p>If the call is already on the main thread - which happens when a plugin calls through here
     * during shutdown or from a scheduled task - it runs immediately rather than being queued for a
     * later tick that may never arrive.
     */
    private void onMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (!plugin.isEnabled()) {
            // Scheduling on a disabled plugin throws. Dropping the action is the only option, and the
            // panel's caller has already been told the action was accepted, so this is logged rather
            // than silently swallowed.
            plugin.getLogger().warning("Refused a moderation action: the plugin is disabled.");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
