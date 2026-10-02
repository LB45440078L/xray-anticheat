package io.xrayac.core.repository;

/**
 * Raised when a persistence operation fails.
 *
 * <p>Deliberately <b>unchecked</b>, and deliberately declared in the core rather than reusing
 * {@code java.sql.SQLException}. The analysis and plugin layers must not be aware that JDBC exists:
 * the repository interfaces are the seam that keeps the storage engine replaceable, and a checked
 * SQL exception in their signatures would drag JDBC into every caller and into the core's compile
 * classpath.
 *
 * <p>Unchecked is the right choice here as well as the convenient one. Every caller of a repository
 * on the plugin side is an asynchronous worker whose correct response to a storage failure is
 * identical in every case: log it, do not lose the in-memory state, and carry on with other
 * players. There is no caller that can meaningfully recover by handling the failure locally, so
 * forcing them to declare and re-handle it would only produce boilerplate that obscures the one
 * place where the failure is actually dealt with.
 */
public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
