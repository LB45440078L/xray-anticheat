package io.xrayac.persistence.jdbc;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.repository.PlayerRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link PlayerRepository}.
 *
 * <h2>Upsert without a dialect-specific statement</h2>
 * The natural way to write "insert or update" is {@code ON CONFLICT} on SQLite/PostgreSQL and
 * {@code ON DUPLICATE KEY UPDATE} on MariaDB — three engines, two incompatible spellings, and a
 * schema that is otherwise entirely portable. This implementation instead tries the update first and
 * inserts only when no row matched, which is one extra round trip in the insert case (a player is
 * seen only once) and zero extra work on the common path.
 *
 * <p>The insert is additionally allowed to fail with a constraint violation and is treated as
 * success in that case: two threads can interleave between the update and the insert, and the loser
 * of that race has still achieved the intended end state — the row exists.
 */
public final class JdbcPlayerRepository extends JdbcRepository implements PlayerRepository {

    private static final String UPDATE_BY_ID =
            "UPDATE players SET name = ?, last_seen = ? WHERE id = ?";
    private static final String INSERT =
            "INSERT INTO players (id, name, first_seen, last_seen) VALUES (?, ?, ?, ?)";
    private static final String SELECT_BY_ID =
            "SELECT id, name, first_seen, last_seen FROM players WHERE id = ?";
    private static final String SELECT_RECENT =
            "SELECT id, name, first_seen, last_seen FROM players ORDER BY last_seen DESC LIMIT ?";

    public JdbcPlayerRepository(ConnectionProvider provider) {
        super(provider);
    }

    @Override
    public void upsert(PlayerRef player, Instant seenAt) {
        long now = seenAt.toEpochMilli();
        write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_BY_ID)) {
                update.setString(1, player.name());
                update.setLong(2, now);
                update.setString(3, player.id().toString());
                if (update.executeUpdate() > 0) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, player.id().toString());
                insert.setString(2, player.name());
                insert.setLong(3, now);
                insert.setLong(4, now);
                insert.executeUpdate();
            } catch (SQLException e) {
                // Another thread inserted the same player between our update and insert; the
                // desired state (the player exists) is already achieved.
                if (!isConstraintViolation(e)) {
                    throw e;
                }
            }
            return null;
        });
    }

    @Override
    public Optional<StoredPlayer> find(UUID id) {
        return read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_BY_ID)) {
                select.setString(1, id.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(map(rows)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<StoredPlayer> mostRecentlySeen(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return read(connection -> {
            List<StoredPlayer> players = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(SELECT_RECENT)) {
                select.setInt(1, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        players.add(map(rows));
                    }
                }
            }
            return players;
        });
    }

    private static StoredPlayer map(ResultSet rows) throws SQLException {
        return new StoredPlayer(
                PlayerRef.of(UUID.fromString(rows.getString("id")), rows.getString("name")),
                Instant.ofEpochMilli(rows.getLong("first_seen")),
                Instant.ofEpochMilli(rows.getLong("last_seen")));
    }

    private static boolean isConstraintViolation(SQLException e) {
        // SQL state 23xxx is the SQL-standard integrity-constraint class and is implemented
        // consistently by SQLite, MariaDB and PostgreSQL, so checking it avoids driver-specific
        // error codes.
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
