package io.b2mash.maestro.integration.support;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A {@link DataSource} over a real {@link PGSimpleDataSource} that, when armed,
 * fails the workflow-event {@code INSERT} issued <em>inside an explicit
 * transaction</em> — the second statement of
 * {@code AbstractJdbcWorkflowStore.completeInstance}.
 *
 * <h2>Why this exact fault</h2>
 * <p>Issue 26's failure is a crash or error <em>between</em> the terminal status
 * write and the terminal event write. Failing the event {@code INSERT} after the
 * status {@code UPDATE} has already executed on the same connection is that
 * failure, delivered deterministically against real Postgres: if the two
 * statements were not one transaction, the {@code UPDATE} would already be
 * committed and the instance would read {@code COMPLETED} with no
 * {@code WORKFLOW_COMPLETED} event — the very state the fix forbids.
 *
 * <p>The fault is scoped by {@link Connection#getAutoCommit()}: only
 * {@code completeInstance} inserts an event with auto-commit off.
 * {@code appendEvent} — every activity, signal and timer memo — goes through the
 * store's auto-commit helper, so an armed source still lets a workflow run
 * normally right up to its finalisation.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: the arm flag and the counter are atomics, and each connection
 * proxy is used by one thread at a time like the connection it wraps.
 */
public final class FaultInjectingDataSource implements DataSource {

    private static final String EVENT_INSERT = "INSERT INTO maestro_workflow_event";

    private final PGSimpleDataSource delegate;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicInteger injectedFailures = new AtomicInteger();

    /** @param delegate the real data source every connection comes from */
    public FaultInjectingDataSource(PGSimpleDataSource delegate) {
        this.delegate = delegate;
    }

    /** Arms the fault: every in-transaction event {@code INSERT} fails from now on. */
    public void failEventInsertsInsideTransactions() {
        armed.set(true);
    }

    /** Disarms the fault: connections behave exactly like the delegate's again. */
    public void disarm() {
        armed.set(false);
    }

    /** @return how many statements this source has failed so far */
    public int injectedFailures() {
        return injectedFailures.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")
                            && args != null && args.length > 0 && args[0] instanceof String sql
                            && armed.get()
                            && sql.contains(EVENT_INSERT)
                            && !connection.getAutoCommit()) {
                        injectedFailures.incrementAndGet();
                        throw new SQLException("injected: event insert");
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
