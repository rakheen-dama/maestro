package io.b2mash.maestro.messaging.postgres;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A {@link DataSource} over a real {@link PGSimpleDataSource} that can be
 * switched into "the database is down" mode.
 *
 * <p>While {@linkplain #arm() armed}, every {@link #getConnection()} throws
 * {@code SQLException("injected: connection refused")} and is counted, which
 * models the store becoming unreachable exactly between a handler's return and
 * the status write that follows it. {@linkplain #disarm() Disarmed}, it
 * delegates unchanged — so the fault is injected without mocks, against the
 * real Postgres the rest of the suite uses.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: the armed flag and the failure counter are atomics, and the
 * delegate is only read.
 */
final class FaultInjectingDataSource implements DataSource {

    private final PGSimpleDataSource delegate;
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final AtomicInteger failedConnections = new AtomicInteger();

    FaultInjectingDataSource(PGSimpleDataSource delegate) {
        this.delegate = delegate;
    }

    /** Makes every subsequent {@code getConnection()} fail until {@link #disarm()}. */
    void arm() {
        armed.set(true);
    }

    /** Restores normal delegation. */
    void disarm() {
        armed.set(false);
    }

    /** @return how many {@code getConnection()} calls were refused while armed */
    int failedConnections() {
        return failedConnections.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        refuseIfArmed();
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        refuseIfArmed();
        return delegate.getConnection(username, password);
    }

    private void refuseIfArmed() throws SQLException {
        if (armed.get()) {
            failedConnections.incrementAndGet();
            throw new SQLException("injected: connection refused");
        }
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
