package io.xrayac.spigot.session;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the live {@link PlayerSession} for every tracked player.
 *
 * <p>Server-thread confined, like the sessions it holds; it is a plain {@link HashMap} for exactly
 * that reason, and the absence of synchronisation is a deliberate assertion that no worker thread
 * ever touches it. Workers receive frozen windows, never sessions.
 *
 * <h2>One session per player, not per player-and-world</h2>
 * A session is scoped to a single world, because ore distributions, exposure geometry and the
 * evidence model are all world-specific and pooling them would compare incomparable things. When a
 * player changes world the existing session is finalised — analysed and persisted — and a fresh one
 * begins. Keeping a session per (player, world) simultaneously would mean holding buffers for worlds
 * the player is not in, which is memory spent on nothing.
 */
public final class SessionRegistry {

    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private final int pathBufferSize;
    private final int miningBufferSize;
    private final int discoveryBufferSize;

    public SessionRegistry(int pathBufferSize, int miningBufferSize, int discoveryBufferSize) {
        this.pathBufferSize = pathBufferSize;
        this.miningBufferSize = miningBufferSize;
        this.discoveryBufferSize = discoveryBufferSize;
    }

    /**
     * Returns the player's session for the given world, creating one if there is none or if the
     * player has moved to a different world.
     *
     * @return the session to use
     */
    public PlayerSession sessionFor(PlayerRef player, WorldId world, Instant now) {
        PlayerSession existing = sessions.get(player.id());
        if (existing != null && existing.world().equals(world)) {
            return existing;
        }
        PlayerSession created = new PlayerSession(player, world, now,
                pathBufferSize, miningBufferSize, discoveryBufferSize);
        sessions.put(player.id(), created);
        return created;
    }

    /**
     * Returns the player's current session if it belongs to the given world.
     *
     * <p>Used by events that must not create a session as a side effect — a movement event for a
     * player in an unanalysed world should do nothing at all, rather than quietly allocate buffers
     * for a world nobody asked to observe.
     */
    public Optional<PlayerSession> current(PlayerRef player, WorldId world) {
        PlayerSession existing = sessions.get(player.id());
        if (existing == null || !existing.world().equals(world)) {
            return Optional.empty();
        }
        return Optional.of(existing);
    }

    public Optional<PlayerSession> get(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    /** Removes and returns a session, used when a player leaves or changes world. */
    public Optional<PlayerSession> remove(UUID playerId) {
        return Optional.ofNullable(sessions.remove(playerId));
    }

    /** Every live session, for the periodic analysis pass and for status reporting. */
    public Collection<PlayerSession> all() {
        return new ArrayList<>(sessions.values());
    }

    public List<PlayerSession> idleSince(Instant cutoff) {
        List<PlayerSession> idle = new ArrayList<>();
        for (PlayerSession session : sessions.values()) {
            if (session.lastActivity().isBefore(cutoff)) {
                idle.add(session);
            }
        }
        return idle;
    }

    /**
     * Removes sessions whose players have been inactive for longer than the timeout.
     *
     * <p>This is the memory-safety valve. Without it, a player who logs off and is subsequently
     * forgotten would retain their buffers for the lifetime of the server, and a busy server would
     * leak memory in proportion to its player turnover.
     *
     * @return the sessions that were removed, so the caller can finalise them
     */
    public List<PlayerSession> pruneIdle(Instant now, Duration idleTimeout) {
        Instant cutoff = now.minus(idleTimeout);
        List<PlayerSession> removed = new ArrayList<>();
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            PlayerSession session = iterator.next().getValue();
            if (session.lastActivity().isBefore(cutoff)) {
                iterator.remove();
                removed.add(session);
            }
        }
        return removed;
    }

    public int size() {
        return sessions.size();
    }

    /** Drops every session, used on shutdown. */
    public List<PlayerSession> drainAll() {
        List<PlayerSession> all = new ArrayList<>(sessions.values());
        sessions.clear();
        return all;
    }
}
