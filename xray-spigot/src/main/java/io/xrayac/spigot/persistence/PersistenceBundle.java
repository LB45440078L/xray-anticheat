package io.xrayac.spigot.persistence;

import io.xrayac.core.repository.BanWaveRepository;
import io.xrayac.core.repository.MiningEventRepository;
import io.xrayac.core.repository.ModeratorActionRepository;
import io.xrayac.core.repository.OreDiscoveryRepository;
import io.xrayac.core.repository.PlayerRepository;
import io.xrayac.core.repository.SuspicionRepository;
import io.xrayac.core.repository.WorldModificationRepository;
import io.xrayac.persistence.ConnectionProvider;
import io.xrayac.persistence.Dialect;
import io.xrayac.persistence.HikariConnectionProvider;
import io.xrayac.persistence.jdbc.JdbcBanWaveRepository;
import io.xrayac.persistence.jdbc.JdbcMiningEventRepository;
import io.xrayac.persistence.jdbc.JdbcModeratorActionRepository;
import io.xrayac.persistence.jdbc.JdbcOreDiscoveryRepository;
import io.xrayac.persistence.jdbc.JdbcPlayerRepository;
import io.xrayac.persistence.jdbc.JdbcSuspicionRepository;
import io.xrayac.persistence.jdbc.JdbcWorldModificationRepository;
import io.xrayac.persistence.migration.MigrationRunner;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the storage layer and its availability state.
 *
 * <h2>Degraded operation is a first-class state, not an accident</h2>
 * A database can be unavailable at startup (the server came up before the database did) or become
 * unavailable later (the database restarted, the network dropped). Neither may take the Minecraft
 * server with it. This class therefore holds an {@code available} flag that the rest of the plugin
 * checks before attempting a write, and a failed attempt flips the flag rather than throwing into the
 * event pipeline.
 *
 * <p>While unavailable the plugin continues to collect observations and to analyse them in memory.
 * Alerts still fire. What stops is persistence and <b>ban-wave enforcement</b> — the latter
 * deliberately, because a wave is supposed to recompute each candidate's evidence from stored data
 * before anyone is removed, and doing that with a broken store would mean banning on numbers nobody
 * could re-derive.
 *
 * <p>Recovery is attempted on a timer. Nothing is buffered indefinitely in the hope of recovery: the
 * in-memory queues have their own bounds and drop the oldest entries, because an unbounded retry
 * buffer converts a database outage into a server outage.
 */
public final class PersistenceBundle implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistenceBundle.class);

    private final ConnectionProvider provider;
    private final Dialect dialect;
    private final int schemaVersion;
    /** Migrations applied by this startup, so the console can say whether the schema moved. */
    private final int migrationsApplied;

    private final PlayerRepository players;
    private final MiningEventRepository miningEvents;
    private final OreDiscoveryRepository oreDiscoveries;
    private final SuspicionRepository suspicion;
    private final BanWaveRepository banWaves;
    private final WorldModificationRepository worldModifications;
    private final ModeratorActionRepository moderatorActions;

    private volatile boolean available;

    private PersistenceBundle(ConnectionProvider provider, Dialect dialect, int schemaVersion,
                              int migrationsApplied, int batchSize) {
        this.provider = provider;
        this.dialect = dialect;
        this.schemaVersion = schemaVersion;
        this.migrationsApplied = migrationsApplied;
        this.players = new JdbcPlayerRepository(provider);
        this.miningEvents = new JdbcMiningEventRepository(provider, batchSize);
        this.oreDiscoveries = new JdbcOreDiscoveryRepository(provider);
        this.suspicion = new JdbcSuspicionRepository(provider);
        this.banWaves = new JdbcBanWaveRepository(provider);
        this.worldModifications = new JdbcWorldModificationRepository(provider, batchSize);
        this.moderatorActions = new JdbcModeratorActionRepository(provider);
    }

    /**
     * Connects, migrates, and returns a usable bundle.
     *
     * @param runMigrations whether to apply pending schema migrations
     * @throws SQLException when the connection or migration fails; the caller decides whether that is
     *         fatal (see {@code storage.fail-safe.continue-without-database})
     */
    public static PersistenceBundle connect(io.xrayac.persistence.DatabaseConfig config,
                                            int batchSize,
                                            boolean runMigrations) throws SQLException {
        ConnectionProvider provider = new HikariConnectionProvider(config);
        int schemaVersion = 0;
        int applied = 0;
        if (runMigrations) {
            MigrationRunner.MigrationReport report = new MigrationRunner(provider).migrate();
            if (report.changedAnything()) {
                LOGGER.info("Applied {} database migration(s)", report.applied().size());
            }
            schemaVersion = report.totalMigrations();
            applied = report.applied().size();
        }
        PersistenceBundle bundle = new PersistenceBundle(provider, provider.dialect(),
                schemaVersion, applied, batchSize);
        bundle.available = true;
        return bundle;
    }

    /** Builds a bundle without a working database, for the degraded start path. */
    public static PersistenceBundle unavailable(io.xrayac.persistence.DatabaseConfig config,
                                                int batchSize) {
        ConnectionProvider provider = new HikariConnectionProvider(config);
        PersistenceBundle bundle = new PersistenceBundle(provider, provider.dialect(), 0, 0, batchSize);
        bundle.available = false;
        return bundle;
    }

    public boolean isAvailable() {
        return available;
    }

    /** Marks the store unusable after a failed operation. */
    public void markUnavailable() {
        available = false;
    }

    /**
     * Attempts to bring the store back, migrating if necessary.
     *
     * @return true when the store is usable
     */
    public boolean attemptRecovery(boolean runMigrations) {
        try {
            if (runMigrations) {
                new MigrationRunner(provider).migrate();
            }
            // A trivial round trip is the only honest proof that the connection works; a pool that
            // has been created but never used proves nothing.
            try (var connection = provider.acquire()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
            }
            available = true;
            LOGGER.info("Database connection restored");
            return true;
        } catch (Exception e) {
            LOGGER.debug("Database recovery attempt failed: {}", e.getMessage());
            return false;
        }
    }

    public PlayerRepository players() {
        return players;
    }

    public MiningEventRepository miningEvents() {
        return miningEvents;
    }

    public OreDiscoveryRepository oreDiscoveries() {
        return oreDiscoveries;
    }

    public SuspicionRepository suspicion() {
        return suspicion;
    }

    public BanWaveRepository banWaves() {
        return banWaves;
    }

    public WorldModificationRepository worldModifications() {
        return worldModifications;
    }

    public ModeratorActionRepository moderatorActions() {
        return moderatorActions;
    }

    public Dialect dialect() {
        return dialect;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** How many schema migrations this startup applied; zero means the schema was already current. */
    public int migrationsApplied() {
        return migrationsApplied;
    }

    /** Pool statistics, or a diagnostic string when the pool is unusable. */
    public String poolStatistics() {
        if (provider instanceof HikariConnectionProvider hikari) {
            return hikari.poolStatistics();
        }
        return "pool statistics unavailable";
    }

    @Override
    public void close() {
        provider.close();
    }
}
