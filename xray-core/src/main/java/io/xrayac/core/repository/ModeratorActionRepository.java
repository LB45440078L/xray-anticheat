package io.xrayac.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The moderator audit trail: who did what to whom, and why.
 *
 * <p>A server must be able to answer "why was this player banned, and who decided?" months after the
 * fact. That question is answerable only if every action — automated or human — is written down with
 * its author and the evidence it rested on. Without this table an automated ban is an unexplainable
 * event, and a moderator's decision leaves no trace beyond their memory.
 *
 * <p><b>Threading contract.</b> Blocking I/O; never call from the server thread.
 */
public interface ModeratorActionRepository {

    /**
     * Records an action.
     *
     * @param moderatorId the acting moderator, or {@code null} when the action was automatic
     * @param note        the reason and, for automated actions, a summary of the evidence
     */
    void record(UUID playerId, UUID moderatorId, String action, String note, Instant performedAt);

    /** Actions taken against a player, newest first. */
    List<StoredAction> forPlayer(UUID playerId, int limit);

    /** Actions taken by a moderator, newest first. */
    List<StoredAction> byModerator(UUID moderatorId, int limit);

    /** A recorded action. */
    record StoredAction(String id, UUID playerId, UUID moderatorId, String action, String note,
                        Instant performedAt) {
    }
}
