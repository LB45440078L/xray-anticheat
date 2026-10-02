package io.xrayac.persistence;

/**
 * The database engines this plugin supports.
 *
 * <p>The dialect is resolved from the JDBC URL rather than configured independently, so that the
 * URL and the driver can never disagree — a configuration where the URL says PostgreSQL and the
 * dialect says MySQL is a class of bug that simply cannot be expressed.
 *
 * <p>Importantly, the schema itself is dialect-neutral (see the header of
 * {@code migrations/V1__initial_schema.sql}): supporting three engines does not mean three sets of
 * SQL. What differs between them is confined to driver selection, connection parameters and a
 * handful of small behavioural quirks, all of which are handled here and in
 * {@link HikariConnectionProvider}.
 */
public enum Dialect {

    SQLITE("org.sqlite.JDBC", "jdbc:sqlite:"),

    MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb:"),

    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql:");

    private final String driverClassName;
    private final String urlPrefix;

    Dialect(String driverClassName, String urlPrefix) {
        this.driverClassName = driverClassName;
        this.urlPrefix = urlPrefix;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String urlPrefix() {
        return urlPrefix;
    }

    /**
     * Resolves the dialect from a JDBC URL.
     *
     * @throws IllegalArgumentException for an unrecognised URL, reported at startup with the
     *         offending URL included. Failing loudly here is deliberate: silently defaulting to
     *         SQLite would create a database file somewhere unintended while the administrator
     *         believed they were talking to PostgreSQL.
     */
    public static Dialect fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("a JDBC URL is required");
        }
        String normalised = jdbcUrl.trim().toLowerCase(java.util.Locale.ROOT);
        for (Dialect dialect : values()) {
            if (normalised.startsWith(dialect.urlPrefix)) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("unrecognised JDBC URL '" + jdbcUrl
                + "'; supported prefixes are " + java.util.Arrays.stream(values())
                        .map(Dialect::urlPrefix)
                        .collect(java.util.stream.Collectors.joining(", ")));
    }

    /** Whether this engine is an embedded single-file database. */
    public boolean isEmbedded() {
        return this == SQLITE;
    }

    /**
     * Whether the driver benefits from explicit transaction control by default.
     *
     * <p>SQLite's JDBC driver runs with autocommit on by default, and for the batch writes this
     * plugin performs, wrapping several statements in one transaction is the difference between one
     * file sync per row and one per batch — a large and easily overlooked performance difference on
     * the server's disk.
     */
    public boolean requiresExplicitTransactions() {
        return true;
    }
}
