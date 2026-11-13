package io.b2mash.maestro.store.jdbc;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store-clock hooks of {@link AbstractJdbcWorkflowStore} without a
 * database (finding 3.7): which statement each method runs and what it binds.
 * Postgres coverage is {@code PostgresDatabaseClockTest}.
 */
class JdbcStoreClockTest {

    @Test
    void getDueTimersByStoreClockRunsTheHookBindingOnlyTheBatchSize() {
        var db = new ScriptedDb();
        var store = new Store(db.dataSource(), "DUE BY STORE CLOCK LIMIT ?");
        store.getDueTimers(7);
        assertEquals(List.of("DUE BY STORE CLOCK LIMIT ?"), db.sql);
        assertEquals(List.of(7), db.parameters);
    }

    @Test
    void withoutTheHookItBindsTheCallersClock() {
        var db = new ScriptedDb();
        var store = new Store(db.dataSource(), null);
        var before = Instant.now();
        store.getDueTimers(7);
        var after = Instant.now();
        assertEquals(List.of("DUE BY CALLER CLOCK ? LIMIT ?"), db.sql);
        assertEquals(2, db.parameters.size(), db.parameters.toString());
        var bound = ((Timestamp) db.parameters.get(0)).toInstant();
        assertTrue(!bound.isBefore(before) && !bound.isAfter(after),
                bound + " not within [" + before + ", " + after + "]");
        assertEquals(7, db.parameters.get(1));
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** The abstract store with the store-clock hook set as given. */
    private static final class Store extends AbstractJdbcWorkflowStore {
        private final @Nullable String storeClockSql;

        Store(DataSource dataSource, @Nullable String storeClockSql) {
            super(dataSource, JdbcStoreConfiguration.withDefaults(JsonMapper.builder().build()));
            this.storeClockSql = storeClockSql;
        }

        @Override
        protected String getDueTimersSql() {
            return "DUE BY CALLER CLOCK ? LIMIT ?";
        }

        @Override
        protected @Nullable String getDueTimersByStoreClockSql() {
            return storeClockSql;
        }
    }

    /**
     * A database that records each prepared statement's SQL and the values
     * bound to it (in parameter order), and answers every query with no rows.
     * Copied from {@code JdbcAppendEventReturningPayloadTest}'s, not shared.
     */
    private static final class ScriptedDb {
        final List<String> sql = new ArrayList<>();
        final List<Object> parameters = new ArrayList<>();

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (p, m, a) -> switch (m.getName()) {
                        case "getConnection" -> connection();
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                        case "prepareStatement" -> {
                            sql.add((String) a[0]);
                            yield statement();
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "executeQuery" -> {
                                return resultSet();
                            }
                            case "close" -> {
                                return null;
                            }
                            default -> {
                                if (m.getName().startsWith("set")) {
                                    parameters.add(a[1]);
                                    return null;
                                }
                                throw new UnsupportedOperationException(m.getName());
                            }
                        }
                    });
        }

        private ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                        case "next" -> false;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }
}
