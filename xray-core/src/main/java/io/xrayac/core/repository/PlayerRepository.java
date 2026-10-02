package io.xrayac.core.repository;

import io.xrayac.core.domain.PlayerRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of player identity.
 *
 * <p>An outbound port declared in the core so that the analysis and plugin layers depend on the
 * abstraction rather than on JDBC. Implementations live in {@code xray-persistence}.
 *
 * <p><b>Threading contract.</b> Every method here performs blocking I/O and must therefore never be
 * called from the Minecraft server thread. The plugin layer dispatches all repository calls to an
 * executor; the interface deliberately offers no synchronous "convenience" method that might tempt a
 * caller to do otherwise.
 */
public interface PlayerRepository {

    /** Inserts the player or updates their name and last-seen time. */
    void upsert(PlayerRef player, Instant seenAt);

    Optional<StoredPlayer> find(UUID id);

    /** Players ordered by most recent activity, for moderator listings and the GUI. */
    List<StoredPlayer> mostRecentlySeen(int limit);

    /** A stored player and its lifetime bounds. */
    record StoredPlayer(PlayerRef player, Instant firstSeen, Instant lastSeen) {
    }
}
