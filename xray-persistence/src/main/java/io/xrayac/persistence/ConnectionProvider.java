package io.xrayac.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies database connections and owns their lifecycle.
 *
 * <p>The abstraction exists so that the repositories never reference HikariCP directly. That keeps
 * the pool a replaceable implementation detail — tests can substitute a provider that hands out
 * plain connections to an embedded database without any pooling at all — and it means a future
 * move to a different pool, or to a driver-managed datasource, touches one class.
 *
 * <p><b>Threading contract.</b> {@link #acquire()} blocks until a connection is available or the
 * configured timeout expires, so it must never be called on the Minecraft server thread.
 * Implementations must be safe for concurrent use, because every analysis worker acquires its own
 * connection.
 */
public interface ConnectionProvider extends AutoCloseable {

    /**
     * Borrows a connection from the pool. The caller must close it, normally via try-with-resources,
     * which returns it to the pool rather than physically closing it.
     *
     * @throws SQLException when no connection can be obtained within the configured timeout
     */
    Connection acquire() throws SQLException;

    /** The dialect this provider is connected to. */
    Dialect dialect();

    /**
     * Whether the pool believes it can serve a connection right now.
     *
     * <p>Used for status reporting and to decide whether to degrade gracefully, not as a
     * precondition for any query: a health check that passes can still be followed by a failed
     * query, and code must handle that regardless.
     */
    boolean isHealthy();

    @Override
    void close();
}
