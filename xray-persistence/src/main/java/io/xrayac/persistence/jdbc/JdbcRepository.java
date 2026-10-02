package io.xrayac.persistence.jdbc;

import io.xrayac.core.repository.PersistenceException;
import io.xrayac.persistence.ConnectionProvider;
import io.xrayac.persistence.TransactionManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Shared plumbing for the JDBC repositories.
 *
 * <p>Exposes exactly two operations, {@link #read} and {@link #write}, so that every repository
 * states plainly whether an operation mutates data. The distinction matters because reads can be
 * issued concurrently on independent pooled connections, while writes are wrapped in a transaction
 * that may hold a lock; making the choice explicit at each call site keeps the two from being
 * confused.
 *
 * <p>This class is also the single place where {@code java.sql.SQLException} is translated into the
 * core's unchecked {@link PersistenceException}. Keeping that translation here — rather than in each
 * of six repositories — means the repositories read as ordinary Java rather than as JDBC ceremony,
 * and there is exactly one place to change if the storage technology ever changes.
 */
abstract class JdbcRepository {

    protected final ConnectionProvider provider;
    protected final TransactionManager transactions;

    protected JdbcRepository(ConnectionProvider provider) {
        this.provider = provider;
        this.transactions = new TransactionManager(provider);
    }

    /** Runs a read on a pooled connection without an explicit transaction. */
    protected <T> T read(TransactionManager.SqlWork<T> work) {
        try {
            return transactions.inConnection(work);
        } catch (SQLException e) {
            throw new PersistenceException("database read failed", e);
        }
    }

    /** Runs a mutating operation inside a transaction. */
    protected <T> T write(TransactionManager.SqlWork<T> work) {
        try {
            return transactions.inTransaction(work);
        } catch (SQLException e) {
            throw new PersistenceException("database write failed", e);
        }
    }

    /** Executes a batch that has accumulated, provided it has reached the configured size. */
    protected static boolean flushIfFull(PreparedStatement statement, int pending, int batchSize)
            throws SQLException {
        if (pending >= batchSize) {
            statement.executeBatch();
            statement.clearBatch();
            return true;
        }
        return false;
    }
}
