package io.xrayac.persistence;

import java.util.Objects;

/**
 * Connection settings for the database, validated at construction.
 *
 * <p>Validation happens here, at startup, rather than at first query. A malformed pool size or a
 * missing URL should stop the plugin from enabling with a clear message, not surface as a mystery
 * failure hours later when a player first trips an alert.
 *
 * @param jdbcUrl                 the JDBC URL; its prefix determines the {@link Dialect}
 * @param username                database user, ignored by embedded databases
 * @param password                database password, ignored by embedded databases
 * @param maximumPoolSize         largest number of pooled connections
 * @param minimumIdle             connections kept warm
 * @param connectionTimeoutMillis how long a caller waits for a connection before failing
 * @param batchSize               rows per JDBC batch on bulk writes
 */
public record DatabaseConfig(
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMillis,
        int batchSize) {

    public DatabaseConfig {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required");
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be at least 1, got " + maximumPoolSize);
        }
        if (minimumIdle < 0) {
            throw new IllegalArgumentException("minimumIdle cannot be negative");
        }
        if (minimumIdle > maximumPoolSize) {
            // A pool that keeps more connections warm than it will ever allow is a configuration
            // contradiction; refusing it avoids a confusing driver-level failure at runtime.
            throw new IllegalArgumentException("minimumIdle (" + minimumIdle
                    + ") cannot exceed maximumPoolSize (" + maximumPoolSize + ")");
        }
        if (connectionTimeoutMillis < 250) {
            // Below a quarter second the pool would report timeouts under ordinary load, which
            // would look like database failure rather than a busy moment.
            throw new IllegalArgumentException("connectionTimeoutMillis must be at least 250");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    public Dialect dialect() {
        return Dialect.fromJdbcUrl(jdbcUrl);
    }

    /** Defaults appropriate for an embedded SQLite database on a small server. */
    public static DatabaseConfig sqlite(String filePath) {
        Objects.requireNonNull(filePath, "filePath");
        return new DatabaseConfig("jdbc:sqlite:" + filePath, "", "", 4, 1, 5_000L, 500);
    }

    /**
     * Defaults appropriate for a remote server.
     *
     * <p>The pool is deliberately modest. A Minecraft server's database traffic is bursty but not
     * heavy, and a large pool is worse than a small one here: it multiplies concurrent writers,
     * increases lock contention on the server's tables, and consumes connections the database
     * itself may need for other plugins. Ten connections is generous for this workload.
     */
    public static DatabaseConfig remote(Dialect dialect, String host, int port, String database,
                                        String username, String password) {
        String url = switch (dialect) {
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database;
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case SQLITE -> throw new IllegalArgumentException(
                    "SQLite is embedded; use DatabaseConfig.sqlite(filePath) instead of a host/port form");
        };
        return new DatabaseConfig(url, username, password, 10, 2, 10_000L, 500);
    }

    /** A copy with a different batch size, for tuning without rebuilding the rest of the config. */
    public DatabaseConfig withBatchSize(int newBatchSize) {
        return new DatabaseConfig(jdbcUrl, username, password, maximumPoolSize, minimumIdle,
                connectionTimeoutMillis, newBatchSize);
    }

    /** The configuration with credentials removed, for safe logging. */
    public String describeWithoutSecrets() {
        return String.format("dialect=%s url=%s pool=%d/%d timeout=%dms batch=%d",
                dialect(), jdbcUrl, minimumIdle, maximumPoolSize, connectionTimeoutMillis, batchSize);
    }
}
