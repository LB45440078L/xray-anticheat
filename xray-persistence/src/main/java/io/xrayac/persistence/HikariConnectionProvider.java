package io.xrayac.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ConnectionProvider} backed by a HikariCP pool.
 *
 * <h2>Why a pool at all</h2>
 * Opening a JDBC connection costs a TCP handshake and, for MariaDB and PostgreSQL, authentication
 * and session setup — tens of milliseconds each time. This plugin writes in bursts (a batch of
 * mining observations every few seconds) and reads on demand, so without pooling every burst would
 * pay that cost, and the latency would land on the workers that keep the evidence pipeline current.
 * A pool turns connection acquisition into a nanosecond-scale hand-off.
 *
 * <h2>SQLite specifics</h2>
 * SQLite is a single file with a database-level write lock, so a large pool buys nothing and can
 * even increase contention. The pool is therefore sized small for SQLite (the config defaults it to
 * four), write-ahead logging is enabled so that readers are not blocked by a writer, and a busy
 * timeout is set so that a competing writer waits rather than failing immediately with
 * {@code SQLITE_BUSY}. These pragmas are applied through the pool's connection-init SQL so that
 * every connection, including ones created later as the pool grows, gets them.
 *
 * <h2>Failure behaviour</h2>
 * The pool does not fail construction when the database is unreachable; it is created with
 * {@code initializationFailTimeout = -1}, which means "start anyway and let the first acquisition
 * report the problem". A plugin that refuses to load because a database is momentarily down is
 * worse than one that loads, logs clearly and keeps trying — the server must come up either way.
 * Callers observe failures as {@link SQLException} from {@link #acquire()} and are expected to
 * degrade rather than propagate.
 */
public final class HikariConnectionProvider implements ConnectionProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(HikariConnectionProvider.class);

    private final Dialect dialect;
    private final HikariDataSource dataSource;

    public HikariConnectionProvider(DatabaseConfig config) {
        this.dialect = config.dialect();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setPoolName("xray-anticheat");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(config.minimumIdle());
        hikari.setConnectionTimeout(config.connectionTimeoutMillis());
        hikari.setAutoCommit(true);

        // Start even if the database is currently unreachable; the first acquire() will surface the
        // problem to a caller that is already prepared to degrade.
        hikari.setInitializationFailTimeout(-1L);

        if (!dialect.isEmbedded()) {
            hikari.setDriverClassName(dialect.driverClassName());
            if (!config.username().isBlank()) {
                hikari.setUsername(config.username());
            }
            hikari.setPassword(config.password());

            // A prepared-statement cache is the single highest-leverage JDBC tuning knob for this
            // workload: the same dozen statements run on every batch.
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "250");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }

        if (dialect == Dialect.SQLITE) {
            // Two pragmas matter for this workload and neither is optional:
            //
            //  * journal_mode=WAL lets readers proceed while a write is in flight. Without it, a
            //    background write blocks the server thread's next read for the duration of the write,
            //    which is precisely the stall this plugin exists to avoid.
            //  * busy_timeout makes a competing writer wait rather than failing immediately with
            //    SQLITE_BUSY, which would otherwise surface as spuriously dropped observations.
            //
            // They are supplied as connection properties because HikariCP accepts only a single
            // connection-init statement and these two cannot share one; the SQLite driver reads these
            // pragma names directly from the connection properties.
            hikari.addDataSourceProperty("journal_mode", "WAL");
            hikari.addDataSourceProperty("busy_timeout", "5000");
            hikari.setMaximumPoolSize(Math.min(config.maximumPoolSize(), 4));
        }

        this.dataSource = new HikariDataSource(hikari);
        LOGGER.info("Database connection pool created ({})", config.describeWithoutSecrets());
    }

    @Override
    public Connection acquire() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public boolean isHealthy() {
        // A closed datasource is definitively unhealthy. Beyond that, the presence of a live pool
        // MXBean means the pool has been initialised and is still managing connections; HikariCP
        // exposes no "suspended" flag, so we do not pretend to check one. A caller relying on this
        // for anything stronger than status reporting would be misusing it — genuine failures are
        // reported by acquire() throwing, which is the only signal that is actually authoritative.
        return !dataSource.isClosed() && dataSource.getHikariPoolMXBean() != null;
    }

    /** Pool statistics, for the {@code /xray status} command. */
    public String poolStatistics() {
        var pool = dataSource.getHikariPoolMXBean();
        if (pool == null) {
            return "pool not initialised";
        }
        return String.format("active=%d idle=%d waiting=%d total=%d",
                pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(), pool.getTotalConnections());
    }

    @Override
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Database connection pool closed");
        }
    }
}
