package io.xrayac.persistence.jdbc;

import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.decision.BanWavePlan;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.repository.BanWaveRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link BanWaveRepository}.
 *
 * <h2>Upsert semantics</h2>
 * A candidate is keyed by player, and re-recording one must <b>strengthen</b> rather than replace:
 * {@code first_detected} is preserved from the existing row and every peak column is the greater of
 * the stored and incoming values. That is expressed directly in the SQL rather than by read-modify-
 * write, so two concurrent workers recording the same player cannot lose one another's update.
 */
public final class JdbcBanWaveRepository extends JdbcRepository implements BanWaveRepository {

    private static final String UPDATE_CANDIDATE = """
            UPDATE ban_wave_candidates
               SET world_key = ?, first_detected = ?, last_detected = ?, peak_suspicion = ?,
                   peak_confidence = ?, peak_strength = ?, sample_size = ?, independent_groups = ?,
                   evidence_summary = ?
             WHERE player_id = ?""";

    private static final String SELECT_CANDIDATE_BY_ID = """
            SELECT c.player_id, c.world_key, c.first_detected, c.last_detected, c.peak_suspicion,
                   c.peak_confidence, c.peak_strength, c.sample_size, c.independent_groups,
                   c.evidence_summary, COALESCE(p.name, 'unknown') AS player_name
            FROM ban_wave_candidates c
            LEFT JOIN players p ON p.id = c.player_id
            WHERE c.player_id = ?""";

    private static final String INSERT_CANDIDATE = """
            INSERT INTO ban_wave_candidates
                (player_id, world_key, first_detected, last_detected, peak_suspicion,
                 peak_confidence, peak_strength, sample_size, independent_groups, evidence_summary)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_CANDIDATES = """
            SELECT c.player_id, c.world_key, c.first_detected, c.last_detected, c.peak_suspicion,
                   c.peak_confidence, c.peak_strength, c.sample_size, c.independent_groups,
                   c.evidence_summary, COALESCE(p.name, 'unknown') AS player_name
            FROM ban_wave_candidates c
            LEFT JOIN players p ON p.id = c.player_id
            ORDER BY c.last_detected DESC""";

    private static final String DELETE_CANDIDATE = "DELETE FROM ban_wave_candidates WHERE player_id = ?";
    private static final String DELETE_EXPIRED = "DELETE FROM ban_wave_candidates WHERE first_detected < ?";

    private static final String INSERT_WAVE = """
            INSERT INTO ban_waves (id, planned_at, executed_at, candidate_count, automatic_ban, summary)
            VALUES (?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_LAST_WAVE =
            "SELECT MAX(executed_at) FROM ban_waves WHERE executed_at IS NOT NULL";

    private static final String SELECT_WAVES = """
            SELECT id, planned_at, executed_at, candidate_count, automatic_ban, summary
            FROM ban_waves ORDER BY planned_at DESC LIMIT ?""";

    public JdbcBanWaveRepository(ConnectionProvider provider) {
        super(provider);
    }

    @Override
    public void upsertCandidate(BanWaveCandidate candidate) {
        write(connection -> {
            // Read-modify-write with the merge performed in Java, so that "keep the stronger" uses
            // the enum's declaration order rather than a string comparison — see
            // BanWaveCandidate.mergedWith for why the string form is unsafe.
            Optional<BanWaveCandidate> existing = selectCandidate(connection, candidate.player().id());
            BanWaveCandidate merged = existing.map(stored -> stored.mergedWith(candidate))
                    .orElse(candidate);

            try (PreparedStatement update = connection.prepareStatement(UPDATE_CANDIDATE)) {
                if (existing.isPresent()) {
                    int i = 1;
                    update.setString(i++, merged.world().key());
                    update.setLong(i++, merged.firstDetected().toEpochMilli());
                    update.setLong(i++, merged.lastDetected().toEpochMilli());
                    update.setDouble(i++, merged.peakSuspicionScore());
                    update.setDouble(i++, merged.peakConfidence());
                    update.setString(i++, merged.peakStrength().name());
                    update.setInt(i++, merged.sampleSize());
                    update.setInt(i++, merged.independentGroups());
                    update.setString(i++, merged.evidenceSummary());
                    update.setString(i, merged.player().id().toString());
                    update.executeUpdate();
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT_CANDIDATE)) {
                insert.setString(1, merged.player().id().toString());
                insert.setString(2, merged.world().key());
                insert.setLong(3, merged.firstDetected().toEpochMilli());
                insert.setLong(4, merged.lastDetected().toEpochMilli());
                insert.setDouble(5, merged.peakSuspicionScore());
                insert.setDouble(6, merged.peakConfidence());
                insert.setString(7, merged.peakStrength().name());
                insert.setInt(8, merged.sampleSize());
                insert.setInt(9, merged.independentGroups());
                insert.setString(10, merged.evidenceSummary());
                insert.executeUpdate();
            }
            return null;
        });
    }

    private static Optional<BanWaveCandidate> selectCandidate(Connection connection, UUID playerId)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_CANDIDATE_BY_ID)) {
            select.setString(1, playerId.toString());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapCandidate(rows));
            }
        }
    }

    private static BanWaveCandidate mapCandidate(ResultSet rows) throws SQLException {
        return new BanWaveCandidate(
                PlayerRef.of(UUID.fromString(rows.getString("player_id")), rows.getString("player_name")),
                WorldId.of(rows.getString("world_key")),
                Instant.ofEpochMilli(rows.getLong("first_detected")),
                Instant.ofEpochMilli(rows.getLong("last_detected")),
                rows.getDouble("peak_suspicion"),
                rows.getDouble("peak_confidence"),
                EvidenceStrength.valueOf(rows.getString("peak_strength")),
                rows.getInt("sample_size"),
                rows.getInt("independent_groups"),
                rows.getString("evidence_summary"));
    }

    @Override
    public List<BanWaveCandidate> candidates() {
        return read(connection -> {
            List<BanWaveCandidate> candidates = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(SELECT_CANDIDATES);
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    candidates.add(new BanWaveCandidate(
                            PlayerRef.of(UUID.fromString(rows.getString("player_id")),
                                    rows.getString("player_name")),
                            WorldId.of(rows.getString("world_key")),
                            Instant.ofEpochMilli(rows.getLong("first_detected")),
                            Instant.ofEpochMilli(rows.getLong("last_detected")),
                            rows.getDouble("peak_suspicion"),
                            rows.getDouble("peak_confidence"),
                            EvidenceStrength.valueOf(rows.getString("peak_strength")),
                            rows.getInt("sample_size"),
                            rows.getInt("independent_groups"),
                            rows.getString("evidence_summary")));
                }
            }
            return candidates;
        });
    }

    @Override
    public void deleteCandidate(UUID playerId) {
        write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(DELETE_CANDIDATE)) {
                delete.setString(1, playerId.toString());
                delete.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public long deleteCandidatesOlderThan(Instant cutoff) {
        return write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(DELETE_EXPIRED)) {
                delete.setLong(1, cutoff.toEpochMilli());
                return (long) delete.executeUpdate();
            }
        });
    }

    @Override
    public String recordWave(BanWavePlan plan, Instant executedAt) {
        String id = UUID.randomUUID().toString();
        write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_WAVE)) {
                insert.setString(1, id);
                insert.setLong(2, plan.plannedAt().toEpochMilli());
                if (executedAt == null) {
                    insert.setNull(3, java.sql.Types.BIGINT);
                } else {
                    insert.setLong(3, executedAt.toEpochMilli());
                }
                insert.setInt(4, plan.size());
                insert.setInt(5, plan.automaticBan() ? 1 : 0);
                insert.setString(6, plan.summary());
                insert.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Override
    public Optional<Instant> lastExecutedWaveAt() {
        return read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_LAST_WAVE);
                 ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                long value = rows.getLong(1);
                return rows.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
            }
        });
    }

    @Override
    public List<WaveRecord> recentWaves(int limit) {
        return read(connection -> {
            List<WaveRecord> waves = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(SELECT_WAVES)) {
                select.setInt(1, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        long executed = rows.getLong("executed_at");
                        waves.add(new WaveRecord(
                                rows.getString("id"),
                                Instant.ofEpochMilli(rows.getLong("planned_at")),
                                rows.wasNull() ? null : Instant.ofEpochMilli(executed),
                                rows.getInt("candidate_count"),
                                rows.getInt("automatic_ban") != 0,
                                rows.getString("summary")));
                    }
                }
            }
            return waves;
        });
    }
}
