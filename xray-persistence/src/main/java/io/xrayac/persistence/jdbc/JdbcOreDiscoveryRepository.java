package io.xrayac.persistence.jdbc;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.repository.OreDiscoveryRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link OreDiscoveryRepository}.
 *
 * <h2>Null handling on read</h2>
 * The alignment columns are nullable, and a NULL is reconstructed as
 * {@link TrajectoryAnalysis.Approach#noData()} rather than as a zero angle. This distinction is
 * load-bearing: "the approach angle was not measurable" and "the approach angle was zero degrees
 * (perfectly aimed)" are opposite conclusions, and collapsing them would fabricate the strongest
 * possible evidence for cheating out of missing path history.
 *
 * <p>Coordinates are read with {@link Math#toIntExact(long)} after the column is fetched as a
 * {@code long}. The schema stores coordinates as BIGINT for headroom; the in-memory model uses
 * {@code int}. If a coordinate ever genuinely exceeded the int range, failing loudly is far better
 * than silently wrapping it into a valid-looking but wrong position.
 */
public final class JdbcOreDiscoveryRepository extends JdbcRepository implements OreDiscoveryRepository {

    private static final String INSERT = """
            INSERT INTO ore_discoveries
                (id, player_id, session_id, world_key, ore_id, x, y, z, discovery_exposure,
                 vein_size, hidden_vein_blocks, exposed_vein_blocks, blocks_since_previous,
                 distance_since_previous, move_alignment_deg, look_alignment_deg,
                 evidence_discount, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_BASE = """
            SELECT id, player_id, world_key, ore_id, x, y, z, discovery_exposure, vein_size,
                   hidden_vein_blocks, exposed_vein_blocks, blocks_since_previous,
                   distance_since_previous, move_alignment_deg, look_alignment_deg,
                   evidence_discount, occurred_at
            FROM ore_discoveries""";

    private static final String DELETE_OLD = "DELETE FROM ore_discoveries WHERE occurred_at < ?";

    public JdbcOreDiscoveryRepository(ConnectionProvider provider) {
        super(provider);
    }

    @Override
    public void save(UUID playerId, String worldKey, String sessionId, OreDiscovery discovery) {
        write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, playerId.toString());
                insert.setString(3, sessionId);
                int index = 4;
                insert.setString(index++, worldKey);
                insert.setString(index++, discovery.oreId());
                insert.setLong(index++, discovery.discoveryBlock().x());
                insert.setLong(index++, discovery.discoveryBlock().y());
                insert.setLong(index++, discovery.discoveryBlock().z());
                insert.setString(index++, discovery.discoveryExposure().name());
                insert.setInt(index++, discovery.veinSize());
                insert.setInt(index++, discovery.hiddenVeinBlocks());
                insert.setInt(index++, discovery.exposedVeinBlocks());
                insert.setDouble(index++, discovery.blocksMinedSincePrevious());
                insert.setDouble(index++, discovery.distanceTravelledSincePrevious());
                setNullableDouble(insert, index++, discovery.approach().hadData()
                        ? discovery.approach().movementAlignmentDegrees() : null);
                setNullableDouble(insert, index++, discovery.approach().hadData()
                        && !Double.isNaN(discovery.approach().lookAlignmentDegrees())
                        ? discovery.approach().lookAlignmentDegrees() : null);
                insert.setDouble(index++, discovery.evidenceDiscount());
                insert.setLong(index, discovery.time().toEpochMilli());
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<StoredDiscovery> findRecent(UUID playerId, Instant since, int limit) {
        String sql = SELECT_BASE + " WHERE player_id = ? AND occurred_at >= ? ORDER BY occurred_at DESC LIMIT ?";
        return read(connection -> {
            List<StoredDiscovery> discoveries = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, playerId.toString());
                select.setLong(2, since.toEpochMilli());
                select.setInt(3, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        discoveries.add(map(rows));
                    }
                }
            }
            return discoveries;
        });
    }

    @Override
    public List<StoredDiscovery> findByWorldAndOre(String worldKey, String oreId, Instant since, int limit) {
        String sql = SELECT_BASE
                + " WHERE world_key = ? AND ore_id = ? AND occurred_at >= ? ORDER BY occurred_at DESC LIMIT ?";
        return read(connection -> {
            List<StoredDiscovery> discoveries = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, worldKey);
                select.setString(2, oreId);
                select.setLong(3, since.toEpochMilli());
                select.setInt(4, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        discoveries.add(map(rows));
                    }
                }
            }
            return discoveries;
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

    private static StoredDiscovery map(ResultSet rows) throws java.sql.SQLException {
        Double moveAngle = nullableDouble(rows, "move_alignment_deg");
        Double lookAngle = nullableDouble(rows, "look_alignment_deg");

        TrajectoryAnalysis.Approach approach;
        if (moveAngle == null) {
            approach = TrajectoryAnalysis.Approach.noData();
        } else {
            approach = new TrajectoryAnalysis.Approach(true, Double.NaN, moveAngle,
                    lookAngle == null ? Double.NaN : lookAngle, 0.0);
        }

        OreDiscovery discovery = new OreDiscovery(
                rows.getString("ore_id"),
                BlockPos.of(
                        Math.toIntExact(rows.getLong("x")),
                        Math.toIntExact(rows.getLong("y")),
                        Math.toIntExact(rows.getLong("z"))),
                Instant.ofEpochMilli(rows.getLong("occurred_at")),
                ExposureState.valueOf(rows.getString("discovery_exposure")),
                rows.getInt("vein_size"),
                rows.getInt("hidden_vein_blocks"),
                rows.getInt("exposed_vein_blocks"),
                rows.getDouble("blocks_since_previous"),
                rows.getDouble("distance_since_previous"),
                approach,
                rows.getDouble("evidence_discount"));

        return new StoredDiscovery(
                rows.getString("id"),
                UUID.fromString(rows.getString("player_id")),
                rows.getString("world_key"),
                discovery);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static Double nullableDouble(ResultSet rows, String column) throws java.sql.SQLException {
        double value = rows.getDouble(column);
        return rows.wasNull() ? null : value;
    }
}
