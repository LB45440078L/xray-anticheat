package io.xrayac.persistence.migration;

import io.xrayac.persistence.ConnectionProvider;
import io.xrayac.persistence.TransactionManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies forward-only schema migrations, exactly once each.
 *
 * <h2>Why migrations and not "CREATE TABLE IF NOT EXISTS everything on startup"</h2>
 * Idempotent creation handles a fresh database and is useless the moment the schema must change.
 * The moment a column is added, an index is retuned, or a table is split, a server that has been
 * running for a year needs the change applied to existing data — without dropping it. A versioned
 * ledger is the only mechanism that can answer "which changes has this database already seen?".
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li><b>Forward-only.</b> Migrations are never rolled back automatically. A rollback that
 *       silently discards a table is a data-loss feature, not a safety one.</li>
 *   <li><b>Additive.</b> Migrations must not drop or destructively alter data. Columns are added
 *       with defaults, tables are renamed rather than rewritten, and anything retired is emptied by
 *       the retention policy rather than by a migration.</li>
 *   <li><b>Atomic per migration.</b> Each migration and its ledger row commit together, so an
 *       interrupted upgrade leaves the database at a consistent version rather than half-migrated
 *       with no record of where it stopped.</li>
 * </ul>
 *
 * <p>Migration files are listed explicitly in {@code migrations/index.txt} rather than discovered by
 * scanning. A directory scan does not work reliably inside a packaged JAR, and the failure mode —
 * silently applying no migrations at all, on a production server — is far worse than the cost of
 * maintaining one line per migration.
 */
public final class MigrationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationRunner.class);

    private static final String INDEX_RESOURCE = "migrations/index.txt";
    private static final String RESOURCE_PREFIX = "migrations/";

    private static final String CREATE_LEDGER = """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version    INTEGER      NOT NULL,
                name       VARCHAR(128) NOT NULL,
                applied_at BIGINT       NOT NULL,
                CONSTRAINT pk_schema_migrations PRIMARY KEY (version)
            )""";

    private final ConnectionProvider provider;
    private final TransactionManager transactions;

    public MigrationRunner(ConnectionProvider provider) {
        this.provider = provider;
        this.transactions = new TransactionManager(provider);
    }

    /**
     * Applies every pending migration.
     *
     * @return a report of what was applied, for logging at startup
     * @throws SQLException when a migration fails; the caller should refuse to run analysis rather
     *         than operate against a half-known schema
     */
    public MigrationReport migrate() throws SQLException {
        transactions.inConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_LEDGER);
            }
            return null;
        });

        List<Migration> all = loadMigrations();
        List<Integer> applied = appliedVersions();

        List<Migration> pending = all.stream()
                .filter(m -> !applied.contains(m.version()))
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();

        List<String> appliedNames = new ArrayList<>();
        for (Migration migration : pending) {
            applyOne(migration);
            appliedNames.add(migration.name());
            LOGGER.info("Applied database migration {}: {}", migration.version(), migration.name());
        }
        if (pending.isEmpty()) {
            LOGGER.info("Database schema is up to date at version {}", currentVersion(all));
        }
        return new MigrationReport(all.size(), appliedNames);
    }

    private void applyOne(Migration migration) throws SQLException {
        transactions.inTransaction(connection -> {
            for (String statementSql : splitStatements(migration.sql())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(statementSql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setLong(3, Instant.now().toEpochMilli());
                insert.executeUpdate();
            }
            return null;
        });
    }

    private List<Integer> appliedVersions() throws SQLException {
        return transactions.inConnection(connection -> {
            List<Integer> versions = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT version FROM schema_migrations")) {
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        versions.add(rows.getInt(1));
                    }
                }
            }
            return versions;
        });
    }

    private int currentVersion(List<Migration> all) {
        return all.stream().mapToInt(Migration::version).max().orElse(0);
    }

    private List<Migration> loadMigrations() throws SQLException {
        List<Migration> migrations = new ArrayList<>();
        try (InputStream indexStream = classpath(INDEX_RESOURCE);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                String sql = readResource(RESOURCE_PREFIX + entry);
                migrations.add(new Migration(parseVersion(entry), entry, sql));
            }
        } catch (IOException e) {
            throw new SQLException("could not read the migration index " + INDEX_RESOURCE, e);
        }
        if (migrations.isEmpty()) {
            throw new SQLException("no migrations were found; the plugin cannot run against an unknown schema");
        }
        return migrations;
    }

    /**
     * Extracts the integer version from a file named {@code V<version>__<description>.sql}.
     */
    private int parseVersion(String fileName) throws SQLException {
        try {
            int start = fileName.indexOf('V') + 1;
            int end = fileName.indexOf("__");
            if (start <= 0 || end < 0) {
                throw new NumberFormatException("missing V<version>__ prefix");
            }
            return Integer.parseInt(fileName.substring(start, end));
        } catch (NumberFormatException e) {
            throw new SQLException("migration file '" + fileName
                    + "' must be named V<version>__<description>.sql", e);
        }
    }

    private InputStream classpath(String resource) throws SQLException {
        InputStream stream = MigrationRunner.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new SQLException("migration resource '" + resource + "' is missing from the plugin jar");
        }
        return stream;
    }

    private String readResource(String resource) throws SQLException, IOException {
        try (InputStream stream = classpath(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits a migration file into individual statements.
     *
     * <p>Comment lines are stripped before splitting, so a semicolon inside a comment cannot
     * truncate a statement. This is a deliberately simple parser rather than a real SQL grammar:
     * migration files are authored by this project and contain no string literals with embedded
     * semicolons, so a full parser would be complexity without corresponding safety.
     */
    static List<String> splitStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder(sql.length());
        for (String line : sql.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String candidate : withoutComments.toString().split(";")) {
            String statement = candidate.trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return statements;
    }

    /**
     * One migration, loaded from the classpath.
     */
    private record Migration(int version, String name, String sql) {
    }

    /**
     * What a migration run did.
     *
     * @param totalMigrations how many migrations exist in total
     * @param applied         the names of those applied in this run, in order
     */
    public record MigrationReport(int totalMigrations, List<String> applied) {

        public MigrationReport {
            applied = List.copyOf(applied);
        }

        public boolean changedAnything() {
            return !applied.isEmpty();
        }
    }
}
