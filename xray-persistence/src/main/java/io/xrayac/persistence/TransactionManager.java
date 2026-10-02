package io.xrayac.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a unit of work inside a single database transaction.
 *
 * <h2>Why this is central rather than incidental</h2>
 * A suspicion snapshot is meaningless without its contributing evidence: the score alone does not
 * explain anything, and an evidence row without its snapshot cannot be interpreted. Writing the two
 * as separate statements means a crash between them leaves a snapshot with missing or, worse,
 * mismatched components. Wrapping them in one transaction makes the pair atomic, so a stored
 * assessment is always complete or absent — never half-present.
 *
 * <p>The same applies to a batch of mining observations: either the batch lands or none of it does,
 * which keeps the trajectory reconstruction from silently losing a middle segment.
 *
 * <h2>Auto-commit handling</h2>
 * The connection's previous auto-commit state is saved and restored rather than assumed. Pooled
 * connections are reused, and a connection returned in the wrong mode would corrupt an unrelated
 * later operation in a way that is extremely hard to diagnose.
 */
public final class TransactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionManager.class);

    private final ConnectionProvider provider;

    public TransactionManager(ConnectionProvider provider) {
        this.provider = provider;
    }

    /**
     * Work performed against a connection, permitted to throw {@link SQLException}.
     */
    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Executes {@code work} in a transaction, committing on success and rolling back on any
     * exception.
     *
     * @throws SQLException if acquiring the connection, committing, or rolling back fails. Rollback
     *         failures are logged but never mask the original exception, because the original
     *         failure is what the caller needs to see.
     */
    public <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = provider.acquire()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        }
    }

    /**
     * Executes {@code work} without an explicit transaction, for single-statement reads where
     * opening a transaction would only add overhead.
     */
    public <T> T inConnection(SqlWork<T> work) throws SQLException {
        try (Connection connection = provider.acquire()) {
            return work.apply(connection);
        }
    }

    private void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // Never replace the original failure: it is the one that explains what happened.
            LOGGER.error("Rollback failed after an error; the connection will be discarded. Original error: {}",
                    original.getMessage(), rollbackFailure);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean previousAutoCommit) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException e) {
            LOGGER.warn("Could not restore the connection's auto-commit state to {}", previousAutoCommit, e);
        }
    }
}
