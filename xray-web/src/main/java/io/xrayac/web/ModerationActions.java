package io.xrayac.web;

import java.util.List;
import java.util.UUID;

/**
 * The server-facing actions the panel can take.
 *
 * <p>An interface rather than a direct call, because this module must not know what a Minecraft server
 * is. The platform adapter implements it; the panel calls it. That keeps the panel free of any
 * Minecraft dependency and, more usefully, lets the whole console be tested against a recording
 * implementation with no server running.
 *
 * <p>Every method returns whether the action actually happened. The panel reports that answer to the
 * operator rather than assuming success: a kick for a player who logged out a moment ago is a
 * perfectly ordinary outcome, and silently reporting it as done would train moderators to distrust
 * the interface.
 */
public interface ModerationActions {

    /** A player currently connected to the server. */
    record OnlinePlayer(UUID id, String name, String world) {
    }

    /** The players online right now. */
    List<OnlinePlayer> online();

    /**
     * Disconnects a player with a reason shown to them.
     *
     * @return true if the player was online and was kicked
     */
    boolean kick(UUID playerId, String reason);

    /**
     * Adds a ban. Implementations decide the duration and whether the player is disconnected.
     *
     * @return true if the ban was recorded
     */
    boolean ban(UUID playerId, String reason);

    /**
     * Lifts a ban.
     *
     * @return true if a ban existed and was removed
     */
    boolean unban(UUID playerId);

    /**
     * Sends a private message to an online player.
     *
     * @return true if the player was online and the message was delivered
     */
    boolean message(UUID playerId, String text);

    /**
     * A player's current name, or null when the server does not know them.
     *
     * <p>Falls back to a stored name in the panel when this returns null, so a player who has not
     * connected since the plugin was installed still shows something readable.
     */
    default String nameOf(UUID playerId) {
        return online().stream()
                .filter(p -> p.id().equals(playerId))
                .map(OnlinePlayer::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * An implementation for when no server is attached - tests, or a panel started outside a server.
     * Every action reports that it did nothing, rather than pretending to succeed.
     */
    static ModerationActions unavailable() {
        return new ModerationActions() {
            @Override
            public List<OnlinePlayer> online() {
                return List.of();
            }

            @Override
            public boolean kick(UUID playerId, String reason) {
                return false;
            }

            @Override
            public boolean ban(UUID playerId, String reason) {
                return false;
            }

            @Override
            public boolean unban(UUID playerId) {
                return false;
            }

            @Override
            public boolean message(UUID playerId, String text) {
                return false;
            }
        };
    }
}
