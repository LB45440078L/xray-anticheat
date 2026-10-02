package io.xrayac.persistence.jdbc;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.repository.SuspicionRepository;
import io.xrayac.persistence.ConnectionProvider;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link SuspicionRepository}.
 *
 * <h2>Atomicity of snapshot and evidence</h2>
 * The snapshot row and its evidence rows are written in a single transaction. A stored assessment
 * without its components would be an unexplainable number, and the whole point of this system is
 * that every conclusion can be explained; a partial write would silently break that guarantee.
 *
 * <h2>Reconstruction fidelity</h2>
 * Components' {@code metrics} maps are not persisted — the schema stores the explanation, which is
 * what a moderator reads, but not the raw named quantities. Reconstructed contributions therefore
 * carry an empty metrics map. This is a deliberate storage/fidelity trade-off: metrics are a
 * convenience for the live GUI, the explanation is the durable record, and duplicating every named
 * quantity per contribution would multiply the size of the largest audit table for a field nothing
 * reads after the fact. The limitation is recorded in {@code docs/DATABASE.md}.
 */
public final class JdbcSuspicionRepository extends JdbcRepository implements SuspicionRepository {

    private static final String INSERT_SNAPSHOT = """
            INSERT INTO suspicion_snapshots
                (id, player_id, world_key, evaluated_at, prior_log_odds, effective_log_odds,
                 posterior_log_odds, suspicion_score, statistical_confidence, decay_factor,
                 sample_size, independent_groups, evidence_strength)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_EVIDENCE = """
            INSERT INTO evidence_events
                (id, snapshot_id, component_id, independent_group, log_likelihood_ratio,
                 reliability, sample_size, explanation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_SNAPSHOTS = """
            SELECT s.id, s.player_id, s.world_key, s.evaluated_at, s.prior_log_odds,
                   s.effective_log_odds, s.posterior_log_odds, s.suspicion_score,
                   s.statistical_confidence, s.decay_factor, s.sample_size,
                   s.independent_groups, s.evidence_strength,
                   COALESCE(p.name, 'unknown') AS player_name
            FROM suspicion_snapshots s
            LEFT JOIN players p ON p.id = s.player_id""";

    private static final String SELECT_EVIDENCE = """
            SELECT component_id, independent_group, log_likelihood_ratio, reliability,
                   sample_size, explanation
            FROM evidence_events WHERE snapshot_id = ?""";

    private static final String DELETE_OLD = "DELETE FROM suspicion_snapshots WHERE evaluated_at < ?";
    private static final String DELETE_ORPHAN_EVIDENCE =
            "DELETE FROM evidence_events WHERE snapshot_id NOT IN (SELECT id FROM suspicion_snapshots)";

    public JdbcSuspicionRepository(ConnectionProvider provider) {
        super(provider);
    }

    @Override
    public String save(SuspicionSnapshot snapshot) {
        String id = UUID.randomUUID().toString();
        write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_SNAPSHOT)) {
                insert.setString(1, id);
                insert.setString(2, snapshot.player().id().toString());
                insert.setString(3, snapshot.world().key());
                insert.setLong(4, snapshot.evaluatedAt().toEpochMilli());
                insert.setDouble(5, snapshot.priorLogOdds());
                insert.setDouble(6, snapshot.effectiveLogOdds());
                insert.setDouble(7, snapshot.posteriorLogOdds());
                insert.setDouble(8, snapshot.suspicionScore());
                insert.setDouble(9, snapshot.statisticalConfidence());
                insert.setDouble(10, snapshot.decayFactor());
                insert.setInt(11, snapshot.sampleSize());
                insert.setInt(12, snapshot.independentGroups());
                insert.setString(13, snapshot.evidenceStrength().name());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT_EVIDENCE)) {
                for (EvidenceContribution contribution : snapshot.contributions()) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, id);
                    insert.setString(3, contribution.componentId());
                    insert.setString(4, contribution.independentGroup());
                    insert.setDouble(5, contribution.logLikelihoodRatio());
                    insert.setDouble(6, contribution.reliability());
                    insert.setInt(7, contribution.sampleSize());
                    insert.setString(8, contribution.explanation());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
        return id;
    }

    @Override
    public List<SuspicionSnapshot> history(UUID playerId, int limit) {
        String sql = SELECT_SNAPSHOTS + " WHERE s.player_id = ? ORDER BY s.evaluated_at DESC LIMIT ?";
        List<RawSnapshot> raw = read(connection -> {
            List<RawSnapshot> results = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                select.setString(1, playerId.toString());
                select.setInt(2, limit);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        results.add(mapRaw(rows));
                    }
                }
            }
            return results;
        });
        return hydrate(raw);
    }

    @Override
    public Optional<SuspicionSnapshot> latest(UUID playerId) {
        List<SuspicionSnapshot> recent = history(playerId, 1);
        return recent.isEmpty() ? Optional.empty() : Optional.of(recent.getFirst());
    }

    @Override
    public long deleteOlderThan(Instant cutoff) {
        return write(connection -> {
            long removed;
            try (PreparedStatement delete = connection.prepareStatement(DELETE_OLD)) {
                delete.setLong(1, cutoff.toEpochMilli());
                removed = delete.executeUpdate();
            }
            // Evidence rows reference snapshots without a foreign key (the schema is intentionally
            // constraint-light so that retention deletes are never blocked by referential integrity
            // checks). They are therefore swept here, in the same transaction, by anti-join.
            try (PreparedStatement sweep = connection.prepareStatement(DELETE_ORPHAN_EVIDENCE)) {
                sweep.executeUpdate();
            }
            return removed;
        });
    }

    private List<SuspicionSnapshot> hydrate(List<RawSnapshot> raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        return read(connection -> {
            List<SuspicionSnapshot> snapshots = new ArrayList<>(raw.size());
            try (PreparedStatement select = connection.prepareStatement(SELECT_EVIDENCE)) {
                for (RawSnapshot snapshot : raw) {
                    List<EvidenceContribution> contributions = new ArrayList<>();
                    select.setString(1, snapshot.id());
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            contributions.add(new EvidenceContribution(
                                    rows.getString("component_id"),
                                    rows.getString("independent_group"),
                                    rows.getDouble("log_likelihood_ratio"),
                                    rows.getInt("sample_size"),
                                    rows.getDouble("reliability"),
                                    rows.getString("explanation"),
                                    Map.of()));
                        }
                    }
                    snapshots.add(snapshot.toSnapshot(contributions));
                }
            }
            return snapshots;
        });
    }

    private static RawSnapshot mapRaw(ResultSet rows) throws java.sql.SQLException {
        return new RawSnapshot(
                rows.getString("id"),
                PlayerRef.of(UUID.fromString(rows.getString("player_id")), rows.getString("player_name")),
                WorldId.of(rows.getString("world_key")),
                Instant.ofEpochMilli(rows.getLong("evaluated_at")),
                rows.getDouble("prior_log_odds"),
                rows.getDouble("effective_log_odds"),
                rows.getDouble("posterior_log_odds"),
                rows.getDouble("suspicion_score"),
                rows.getDouble("statistical_confidence"),
                rows.getDouble("decay_factor"),
                rows.getInt("sample_size"),
                rows.getInt("independent_groups"),
                EvidenceStrength.valueOf(rows.getString("evidence_strength")));
    }

    /**
     * A snapshot row before its evidence rows have been fetched.
     */
    private record RawSnapshot(
            String id, PlayerRef player, WorldId world, Instant evaluatedAt,
            double priorLogOdds, double effectiveLogOdds, double posteriorLogOdds,
            double suspicionScore, double statisticalConfidence, double decayFactor,
            int sampleSize, int independentGroups, EvidenceStrength strength) {

        SuspicionSnapshot toSnapshot(List<EvidenceContribution> contributions) {
            return new SuspicionSnapshot(player, world, evaluatedAt, priorLogOdds, effectiveLogOdds,
                    posteriorLogOdds, suspicionScore, statisticalConfidence, decayFactor,
                    sampleSize, independentGroups, strength, contributions);
        }
    }
}
