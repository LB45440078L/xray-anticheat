package io.xrayac.persistence.jdbc;

import io.xrayac.core.repository.ModeratorActionRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link ModeratorActionRepository}.
 *
 * <p>A {@code null} moderator identifier means the action was automatic. That distinction is stored
 * rather than encoded as a sentinel player, because "a moderator decided this" and "the plugin decided
 * this" are different claims with different accountability, and a report that blurred them would be
 * worse than no report.
 */
public final class JdbcModeratorActionRepository extends JdbcRepository
        implements ModeratorActionRepository {

    private static final String INSERT = """
            INSERT INTO moderator_actions (id, player_id, moderator_id, action, note, performed_at)
            VALUES (?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_BY_PLAYER = """
            SELECT id, player_id, moderator_id, action, note, performed_at
            FROM moderator_actions WHERE player_id = ? ORDER BY performed_at DESC LIMIT ?""";

    private static final String SELECT_BY_MODERATOR = """
            SELECT id, player_id, moderator_id, action, note, performed_at
            FROM moderator_actions WHERE moderator_id = ? ORDER BY performed_at DESC LIMIT ?""";

    public JdbcModeratorActionRepository(ConnectionProvider provider) {
        super(provider);
    }

    @Override
    public void record(UUID playerId, UUID moderatorId, String action, String note, Instant performedAt) {
        write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, playerId.toString());
                if (moderatorId == null) {
                    insert.setNull(3, Types.VARCHAR);
                } else {
                    insert.setString(3, moderatorId.toString());
                }
                insert.setString(4, action);
                insert.setString(5, note);
                insert.setLong(6, performedAt.toEpochMilli());
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<StoredAction> forPlayer(UUID playerId, int limit) {
        return query(SELECT_BY_PLAYER, playerId, limit);
    }

    @Override
    public List<StoredAction> byModerator(UUID moderatorId, int limit) {
        return query(SELECT_BY_MODERATOR, moderatorId, limit);
    }

    private List<StoredAction> query(String sql, UUID id, int limit) {
        return read(connection -> {
            List<StoredAction> actions = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, id.toString());
                select.setInt(2, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        actions.add(map(rows));
                    }
                }
            }
            return actions;
        });
    }

    private static StoredAction map(ResultSet rows) throws SQLException {
        String moderator = rows.getString("moderator_id");
        return new StoredAction(
                rows.getString("id"),
                UUID.fromString(rows.getString("player_id")),
                moderator == null ? null : UUID.fromString(moderator),
                rows.getString("action"),
                rows.getString("note"),
                Instant.ofEpochMilli(rows.getLong("performed_at")));
    }
}
