package io.xrayac.persistence.jdbc;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.MiningEventRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link MiningEventRepository}.
 *
 * <p>Reads join the {@code players} table to recover the display name. The name is stored once, in
 * one place, rather than denormalised onto every event row: a player who renames would otherwise
 * leave a trail of stale names across millions of historical rows, and the storage cost of the
 * duplication would be considerable for a value that is only ever needed for display.
 */
public final class JdbcMiningEventRepository extends JdbcRepository implements MiningEventRepository {

    private static final String INSERT = """
            INSERT INTO mining_events
                (id, player_id, session_id, world_key, x, y, z, block_key, origin, tick, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_RECENT = """
            SELECT m.world_key, m.x, m.y, m.z, m.block_key, m.origin, m.tick, m.occurred_at,
                   COALESCE(p.name, 'unknown') AS player_name
            FROM mining_events m
            LEFT JOIN players p ON p.id = m.player_id
            WHERE m.player_id = ? AND m.occurred_at >= ?
            ORDER BY m.occurred_at DESC
            LIMIT ?""";

    private static final String COUNT_SINCE =
            "SELECT COUNT(*) FROM mining_events WHERE player_id = ? AND world_key = ? AND occurred_at >= ?";
    private static final String DELETE_OLD = "DELETE FROM mining_events WHERE occurred_at < ?";

    private final int batchSize;

    public JdbcMiningEventRepository(ConnectionProvider provider, int batchSize) {
        super(provider);
        this.batchSize = batchSize;
    }

    @Override
    public void saveAll(List<Observation.Mining> events) {
        if (events.isEmpty()) {
            return;
        }
        write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                int pending = 0;
                for (Observation.Mining event : events) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, event.player().id().toString());
                    insert.setString(3, null);
                    insert.setString(4, event.world().key());
                    insert.setLong(5, event.blockPos().x());
                    insert.setLong(6, event.blockPos().y());
                    insert.setLong(7, event.blockPos().z());
                    insert.setString(8, event.blockType());
                    insert.setString(9, event.origin().name());
                    insert.setLong(10, event.tick());
                    insert.setLong(11, event.timestamp().toEpochMilli());
                    insert.addBatch();
                    pending++;
                    if (flushIfFull(insert, pending, batchSize)) {
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                }
            }
            return null;
        });
    }

    @Override
    public List<Observation.Mining> findRecent(UUID playerId, Instant since, int limit) {
        return read(connection -> {
            List<Observation.Mining> events = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(SELECT_RECENT)) {
                select.setString(1, playerId.toString());
                select.setLong(2, since.toEpochMilli());
                select.setInt(3, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        events.add(new Observation.Mining(
                                PlayerRef.of(playerId, rows.getString("player_name")),
                                WorldId.of(rows.getString("world_key")),
                                rows.getLong("tick"),
                                Instant.ofEpochMilli(rows.getLong("occurred_at")),
                                BlockPos.of(rows.getInt("x"), rows.getInt("y"), rows.getInt("z")),
                                rows.getString("block_key"),
                                MiningOrigin.valueOf(rows.getString("origin"))));
                    }
                }
            }
            return events;
        });
    }

    @Override
    public long countSince(UUID playerId, String worldKey, Instant since) {
        return read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(COUNT_SINCE)) {
                select.setString(1, playerId.toString());
                select.setString(2, worldKey);
                select.setLong(3, since.toEpochMilli());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public long deleteOlderThan(Instant cutoff) {
        return write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(DELETE_OLD)) {
                delete.setLong(1, cutoff.toEpochMilli());
                return (long) delete.executeUpdate();
            }
        });
    }
}
