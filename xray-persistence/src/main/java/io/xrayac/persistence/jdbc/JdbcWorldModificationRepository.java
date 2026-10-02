package io.xrayac.persistence.jdbc;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.WorldModificationRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link WorldModificationRepository}: the excavation ledger.
 *
 * <p>Reads are exact-coordinate lookups on the analysis path and are served by the covering index
 * on (world_key, x, y, z). Writes are batched, because block breaks arrive far too fast to issue one
 * statement each.
 */
public final class JdbcWorldModificationRepository extends JdbcRepository
        implements WorldModificationRepository {

    private static final String INSERT = """
            INSERT INTO world_modifications (id, world_key, x, y, z, origin, actor_id, removed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String SELECT_ORIGIN =
            "SELECT origin FROM world_modifications WHERE world_key = ? AND x = ? AND y = ? AND z = ?";
    private static final String SELECT_REMOVED_AT =
            "SELECT removed_at FROM world_modifications WHERE world_key = ? AND x = ? AND y = ? AND z = ?";
    private static final String DELETE_OLD = "DELETE FROM world_modifications WHERE removed_at < ?";
    private static final String COUNT_ALL = "SELECT COUNT(*) FROM world_modifications";

    private final int batchSize;

    public JdbcWorldModificationRepository(ConnectionProvider provider, int batchSize) {
        super(provider);
        this.batchSize = batchSize;
    }

    @Override
    public void record(WorldId world, BlockPos pos, MiningOrigin origin, UUID actorId, Instant removedAt) {
        recordAll(List.of(new Removal(world, pos, origin, actorId, removedAt)));
    }

    @Override
    public void recordAll(List<Removal> removals) {
        if (removals.isEmpty()) {
            return;
        }
        write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                int pending = 0;
                for (Removal removal : removals) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, removal.world().key());
                    insert.setLong(3, removal.pos().x());
                    insert.setLong(4, removal.pos().y());
                    insert.setLong(5, removal.pos().z());
                    insert.setString(6, removal.origin().name());
                    insert.setString(7, removal.actorId() == null ? null : removal.actorId().toString());
                    insert.setLong(8, removal.removedAt().toEpochMilli());
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
    public Optional<MiningOrigin> originAt(WorldId world, BlockPos pos) {
        return read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_ORIGIN)) {
                bindPosition(select, world, pos);
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(MiningOrigin.valueOf(rows.getString(1)));
                }
            }
        });
    }

    @Override
    public long removedAtEpochMillis(WorldId world, BlockPos pos) {
        return read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_REMOVED_AT)) {
                bindPosition(select, world, pos);
                try (ResultSet rows = select.executeQuery()) {
                    // -1 rather than 0 for "unknown": epoch 0 is a representable instant, and a
                    // caller comparing timestamps must be able to distinguish absence from 1970.
                    return rows.next() ? rows.getLong(1) : -1L;
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

    @Override
    public long count() {
        return read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(COUNT_ALL);
                 ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        });
    }

    private static void bindPosition(PreparedStatement statement, WorldId world, BlockPos pos)
            throws java.sql.SQLException {
        statement.setString(1, world.key());
        statement.setLong(2, pos.x());
        statement.setLong(3, pos.y());
        statement.setLong(4, pos.z());
    }
}
