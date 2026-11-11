package io.b2mash.maestro.store.postgres.lock;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * A {@link DataSource} over a real one that records the SQL of every
 * {@link PreparedStatement} it executes, so a test can assert how many round
 * trips an operation cost.
 *
 * <p>Connections and prepared statements are wrapped in JDK dynamic proxies;
 * an execution is any {@code execute*} call on a prepared statement, recorded
 * with the SQL it was prepared from. Everything else delegates unchanged.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: the record is a {@link CopyOnWriteArrayList} and the
 * delegate is only read.
 */
final class StatementCountingDataSource implements DataSource {

    private final DataSource delegate;
    private final List<String> executed = new CopyOnWriteArrayList<>();

    StatementCountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** Forgets every execution recorded so far. */
    void reset() {
        executed.clear();
    }

    /**
     * @param verb the leading SQL keyword, e.g. {@code "UPDATE"}
     * @return how many executed statements begin with {@code verb}
     */
    long executedStartingWith(String verb) {
        var prefix = verb.toUpperCase(Locale.ROOT);
        return executed.stream()
                .filter(sql -> sql.strip().toUpperCase(Locale.ROOT).startsWith(prefix))
                .count();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return countingConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return countingConnection(delegate.getConnection(username, password));
    }

    private Connection countingConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (_, method, args) -> {
                    var result = invoke(connection, method, args);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement ps) {
                        return countingStatement(ps, (String) args[0]);
                    }
                    return result;
                });
    }

    private PreparedStatement countingStatement(PreparedStatement statement, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (_, method, args) -> {
                    if (method.getName().startsWith("execute")) {
                        executed.add(sql);
                    }
                    return invoke(statement, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
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
