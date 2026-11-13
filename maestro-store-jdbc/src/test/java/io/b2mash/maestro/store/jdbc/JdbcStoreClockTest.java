package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.exception.StoreUnavailableException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void storeTimeRunsTheHook() {
        var fixed = Instant.parse("2026-11-13T10:00:00.123456Z");
        var db = new ScriptedDb().row(OffsetDateTime.ofInstant(fixed, ZoneOffset.ofHours(2)));
        var store = new Store(db.dataSource(), null, "STORE NOW");
        assertEquals(Optional.of(fixed), store.storeTime());
        assertEquals(List.of("STORE NOW"), db.sql);
        assertEquals(List.of(), db.parameters);
    }

    @Test
    void withoutTheHookStoreTimeIsEmptyAndRunsNoSql() {
        var db = new ScriptedDb();
        var store = new Store(db.dataSource(), null, null);
        assertEquals(Optional.empty(), store.storeTime());
        assertEquals(List.of(), db.sql);
    }

    @Test
    void aStoreTimeSqlErrorIsTranslatedAsEveryReadIs() {
        var db = new ScriptedDb().failing("08006");
        var store = new Store(db.dataSource(), null, "STORE NOW");
        var fault = assertThrows(StoreUnavailableException.class, store::storeTime);
        assertEquals("08006", ((SQLException) fault.getCause()).getSQLState());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** The abstract store with the store-clock hook set as given. */
    private static final class Store extends AbstractJdbcWorkflowStore {
        private final @Nullable String storeClockSql;
        private final @Nullable String storeTimeSql;

        Store(DataSource dataSource, @Nullable String storeClockSql) {
            this(dataSource, storeClockSql, null);
        }

        Store(DataSource dataSource, @Nullable String storeClockSql, @Nullable String storeTimeSql) {
            super(dataSource, JdbcStoreConfiguration.withDefaults(JsonMapper.builder().build()));
            this.storeClockSql = storeClockSql;
            this.storeTimeSql = storeTimeSql;
        }

        @Override
        protected @Nullable String storeTimeSql() {
            return storeTimeSql;
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
     * bound to it (in parameter order), and answers every query with no rows
     * — or with one row whose every {@code getObject} is {@link #row}, or with
     * a scripted SQL failure. Copied from
     * {@code JdbcAppendEventReturningPayloadTest}'s, not shared.
     */
    private static final class ScriptedDb {
        final List<String> sql = new ArrayList<>();
        final List<Object> parameters = new ArrayList<>();
        private @Nullable Object row;
        private @Nullable String failState;

        ScriptedDb row(Object value) {
            this.row = value;
            return this;
        }

        ScriptedDb failing(String sqlState) {
            this.failState = sqlState;
            return this;
        }

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
                                if (failState != null) {
                                    throw new SQLException("scripted failure", failState);
                                }
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
            var remaining = new int[]{row != null ? 1 : 0};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                        case "next" -> remaining[0]-- > 0;
                        case "getObject" -> row;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }
}
