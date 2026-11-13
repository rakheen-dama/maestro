package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.FencedWriteException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.spi.RunLease;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbstractJdbcWorkflowStore#appendEventReturningPayload} without a
 * database (WP1 Task 7): the one statement each hook setting runs, what each
 * returns, and that the {@code RETURNING} insert maps a stale lease, a unique
 * violation and a store fault exactly as {@code appendEvent(event, lease)}
 * does. Postgres coverage is {@code PostgresPayloadFidelityTest}.
 */
@DisplayName("AbstractJdbcWorkflowStore.appendEventReturningPayload")
class JdbcAppendEventReturningPayloadTest {

    private static final UUID INSTANCE = UUID.randomUUID();
    private static final RunLease LEASE = new RunLease(INSTANCE, 0);

    private static WorkflowEvent event(@Nullable JsonNode payload) {
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE, 1, EventType.ACTIVITY_COMPLETED,
                "Probes.probe", payload, Instant.now());
    }

    // ── hook off: the text round trip ─────────────────────────────────────

    @Test
    @DisplayName("hook off: one executeUpdate, no RETURNING, and the text round trip of what was written")
    void hookOff_returnsTheTextRoundTripOfTheWrittenText() throws SQLException {
        var db = new ScriptedDb().updateCount(1);
        var store = new Store(db.dataSource(), false);
        var sent = JsonNodeFactory.instance.objectNode().put("aa", 1).put("x", 0.1);

        var returned = store.appendEventReturningPayload(event(sent), LEASE);

        // What a text column holding the written text reads back.
        var reread = store.getJsonValue(textColumn(db.writtenPayloadText), "payload");
        assertAll(
                () -> assertEquals(List.of("executeUpdate"), db.executions),
                () -> assertTrue(!db.sql.getFirst().contains("RETURNING"), db.sql.getFirst()),
                () -> assertEquals(reread, returned),
                () -> assertEquals(String.valueOf(reread), String.valueOf(returned)),
                () -> assertEquals("DecimalNode", returned.get("x").getClass().getSimpleName(),
                        "parsed by the store's exact-decimal reader, as a later read is"));
    }

    @Test
    @DisplayName("hook off: a null or JSON-null payload is written as SQL NULL and returned as null")
    void hookOff_nullPayloadReturnsNull() {
        var db = new ScriptedDb().updateCount(1);
        var store = new Store(db.dataSource(), false);
        assertNull(store.appendEventReturningPayload(event(null), LEASE));
        assertNull(store.appendEventReturningPayload(event(NullNode.getInstance()), LEASE));
    }

    @Test
    @DisplayName("hook off: a stale lease is a FencedWriteException")
    void hookOff_staleLeaseIsFenced() {
        var store = new Store(new ScriptedDb().updateCount(0).dataSource(), false);
        assertThrows(FencedWriteException.class, () -> store.appendEventReturningPayload(event(null), LEASE));
    }

    // ── hook on: INSERT … RETURNING payload ───────────────────────────────

    @Test
    @DisplayName("hook on: one executeQuery of the fenced INSERT + RETURNING payload; the returned column, parsed as a read parses it")
    void hookOn_returnsTheReturnedColumnParsedAsAReadParsesIt() throws SQLException {
        var db = new ScriptedDb().returning("{\"b\": 2, \"aa\": 1, \"x\": 10000000}");
        var store = new Store(db.dataSource(), true);

        var returned = store.appendEventReturningPayload(
                event(JsonNodeFactory.instance.objectNode().put("aa", 1).put("b", 2).put("x", 1.0E7)), LEASE);

        var reread = store.getJsonValue(textColumn("{\"b\": 2, \"aa\": 1, \"x\": 10000000}"), "payload");
        var fencedInsert = new ScriptedDb().updateCount(1);
        new Store(fencedInsert.dataSource(), false).appendEvent(event(null), LEASE);
        assertAll(
                () -> assertEquals(List.of("executeQuery"), db.executions),
                () -> assertEquals(fencedInsert.sql.getFirst() + " RETURNING payload", db.sql.getFirst()),
                () -> assertEquals(String.valueOf(reread), String.valueOf(returned)),
                () -> assertEquals("{\"b\":2,\"aa\":1,\"x\":10000000}", String.valueOf(returned)));
    }

    @Test
    @DisplayName("hook on: a SQL NULL column is returned as null")
    void hookOn_sqlNullIsNull() {
        var store = new Store(new ScriptedDb().returning(null).dataSource(), true);
        assertNull(store.appendEventReturningPayload(event(null), LEASE));
    }

    @Test
    @DisplayName("hook on: no row is a FencedWriteException")
    void hookOn_noRowIsFenced() {
        var store = new Store(new ScriptedDb().returningNoRow().dataSource(), true);
        var fenced = assertThrows(FencedWriteException.class,
                () -> store.appendEventReturningPayload(event(null), LEASE));
        assertTrue(fenced.getMessage().contains("appendEvent"), fenced.getMessage());
    }

    @Test
    @DisplayName("hook on: a unique violation is a DuplicateEventException; nothing is returned")
    void hookOn_uniqueViolationIsDuplicate() {
        var store = new Store(new ScriptedDb().failing("23505").dataSource(), true);
        var duplicate = assertThrows(DuplicateEventException.class,
                () -> store.appendEventReturningPayload(event(null), LEASE));
        assertEquals("23505", ((SQLException) duplicate.getCause()).getSQLState());
    }

    @Test
    @DisplayName("hook on: a connection fault is a StoreUnavailableException, classified as appendEvent's")
    void hookOn_connectionFaultIsAStoreFault() {
        var store = new Store(new ScriptedDb().failing("08006").dataSource(), true);
        var fault = assertThrows(StoreUnavailableException.class,
                () -> store.appendEventReturningPayload(event(null), LEASE));
        assertTrue(fault.getMessage().endsWith(" RETURNING payload"), fault.getMessage());
    }

    @Test
    @DisplayName("hook on: a returned column the parser rejects returns the payload that was sent, without throwing")
    void hookOn_unparseableReturnsTheSentPayload() {
        var store = new Store(new ScriptedDb().returning("{not json").dataSource(), true);
        var sent = JsonNodeFactory.instance.objectNode().put("a", 1);
        assertSame(sent, store.appendEventReturningPayload(event(sent), LEASE));
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** The abstract store with the hook set as given; nothing else overridden. */
    private static final class Store extends AbstractJdbcWorkflowStore {
        private final boolean returning;

        Store(DataSource dataSource, boolean returning) {
            super(dataSource, JdbcStoreConfiguration.withDefaults(JsonMapper.builder().build()));
            this.returning = returning;
        }

        @Override
        protected boolean returnsInsertedPayload() {
            return returning;
        }

        @Override
        protected String getDueTimersSql() {
            throw new UnsupportedOperationException("not exercised");
        }
    }

    /** A result set whose every {@code getString} returns {@code text}; nothing else is used. */
    private static ResultSet textColumn(@Nullable String text) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getString")) {
                        return text;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * A database that answers each prepared statement as scripted and records
     * the SQL, the execute method used and the payload text bound.
     */
    private static final class ScriptedDb {
        final List<String> sql = new ArrayList<>();
        final List<String> executions = new ArrayList<>();
        @Nullable String writtenPayloadText;
        private int updateCount;
        private boolean row = true;
        private @Nullable String returnedText;
        private @Nullable String failState;

        ScriptedDb updateCount(int n) {
            this.updateCount = n;
            return this;
        }

        ScriptedDb returning(@Nullable String text) {
            this.returnedText = text;
            return this;
        }

        ScriptedDb returningNoRow() {
            this.row = false;
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
                            case "setString" -> {
                                if ((int) a[0] == 6) {
                                    writtenPayloadText = (String) a[1];
                                }
                                return null;
                            }
                            case "executeUpdate", "executeQuery" -> {
                                executions.add(m.getName());
                                if (failState != null) {
                                    throw new SQLException("scripted failure", failState);
                                }
                                return m.getName().equals("executeUpdate") ? updateCount : resultSet();
                            }
                            case "close" -> {
                                return null;
                            }
                            default -> {
                                if (m.getName().startsWith("set")) {
                                    return null;
                                }
                                throw new UnsupportedOperationException(m.getName());
                            }
                        }
                    });
        }

        private ResultSet resultSet() {
            var remaining = new int[]{row ? 1 : 0};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                        case "next" -> remaining[0]-- > 0;
                        case "getString" -> returnedText;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }
}
