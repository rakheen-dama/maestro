package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbstractJdbcWorkflowStore#createInstance} hands back the instance
 * with its input as the store will read it back (Issue 43), without a
 * database: with {@link AbstractJdbcWorkflowStore#returnsInsertedPayload()}
 * on, the instance insert carries {@code RETURNING input} and returns the
 * parsed column; off, it returns the text round trip of what it wrote — what
 * a text column hands back. Postgres coverage is
 * {@code PostgresPayloadFidelityTest.createInstanceReturnsTheInputGetInstanceReads}.
 */
@DisplayName("AbstractJdbcWorkflowStore.createInstance returns the stored input")
class JdbcCreateInstanceReturningInputTest {

    private static WorkflowInstance instance(@Nullable JsonNode input) {
        var now = Instant.now();
        return WorkflowInstance.builder()
                .id(UUID.randomUUID()).workflowId("wf-" + UUID.randomUUID()).runId(UUID.randomUUID())
                .workflowType("T").taskQueue("default").status(WorkflowStatus.RUNNING)
                .input(input).serviceName("svc").eventSequence(0).startedAt(now).updatedAt(now).version(0)
                .build();
    }

    @Test
    @DisplayName("hook off: the instance insert runs as an update and the input returned is the text round trip")
    void hookOff_returnsTheTextRoundTripOfTheWrittenInput() {
        var db = new ScriptedDb();
        var store = new Store(db.dataSource(), false);
        var sent = JsonNodeFactory.instance.objectNode().put("aa", 1).put("x", 0.1);

        var created = store.createInstance(instance(sent));

        assertAll(
                () -> assertEquals(1, db.sql.size(), "createInstance writes the instance row and nothing else: "
                        + "the engine adopts orphaned signals (Issue 83) " + db.sql),
                () -> assertTrue(!db.sql.getFirst().contains("RETURNING"), db.sql.getFirst()),
                () -> assertEquals("executeUpdate", db.executions.getFirst()),
                () -> assertEquals(sent, JsonMapper.builder().build().readTree(db.writtenInputText)),
                () -> assertNotSame(sent, created.input(), "a parsed copy, not the node sent"),
                () -> assertEquals("{\"aa\":1,\"x\":0.1}", String.valueOf(created.input())),
                () -> assertEquals("DecimalNode", created.input().get("x").getClass().getSimpleName(),
                        "parsed by the store's exact-decimal reader, as a later read is"));
    }

    @Test
    @DisplayName("hook on: the instance insert carries RETURNING input and returns the column as parsed")
    void hookOn_returnsTheReturnedColumn() {
        var db = new ScriptedDb().returning("{\"b\": 2, \"aa\": 1}");
        var store = new Store(db.dataSource(), true);
        var sent = JsonNodeFactory.instance.objectNode().put("aa", 1).put("b", 2);

        var created = store.createInstance(instance(sent));

        assertAll(
                () -> assertTrue(db.sql.getFirst().endsWith(" RETURNING input"), db.sql.getFirst()),
                () -> assertEquals("executeQuery", db.executions.getFirst()),
                () -> assertEquals("{\"b\":2,\"aa\":1}", String.valueOf(created.input()),
                        "the store's form, property order included"));
    }

    @Test
    @DisplayName("a null or JSON-null input is written as SQL NULL and returned as null, either hook")
    void aNullInputReturnsNull() {
        for (var returning : List.of(false, true)) {
            for (var input : new JsonNode[]{null, NullNode.getInstance()}) {
                var db = new ScriptedDb().returning(null);
                assertNull(new Store(db.dataSource(), returning).createInstance(instance(input)).input(),
                        "returning=" + returning + " input=" + input);
            }
        }
    }

    @Test
    @DisplayName("every other field is the instance given")
    void theOtherFieldsAreUnchanged() {
        var db = new ScriptedDb().returning("{\"a\": 1}");
        var given = instance(JsonNodeFactory.instance.objectNode().put("a", 1));

        var created = new Store(db.dataSource(), true).createInstance(given);

        assertAll(
                () -> assertEquals(given.id(), created.id()),
                () -> assertEquals(given.workflowId(), created.workflowId()),
                () -> assertEquals(given.runId(), created.runId()),
                () -> assertEquals(given.workflowType(), created.workflowType()),
                () -> assertEquals(given.taskQueue(), created.taskQueue()),
                () -> assertEquals(given.status(), created.status()),
                () -> assertEquals(given.serviceName(), created.serviceName()),
                () -> assertEquals(given.eventSequence(), created.eventSequence()),
                () -> assertEquals(given.startedAt(), created.startedAt()),
                () -> assertEquals(given.updatedAt(), created.updatedAt()),
                () -> assertEquals(given.version(), created.version()),
                () -> assertEquals(given.leaseEpoch(), created.leaseEpoch()),
                () -> assertEquals(given.output(), created.output()));
    }

    @Test
    @DisplayName("hook on: a returned input the store cannot parse fails createInstance and rolls back")
    void hookOn_anUnparseableReturnedInputRollsBack() {
        var db = new ScriptedDb().returning("{not json");
        var store = new Store(db.dataSource(), true);

        org.junit.jupiter.api.Assertions.assertThrows(io.b2mash.maestro.core.exception.SerializationException.class,
                () -> store.createInstance(instance(JsonNodeFactory.instance.objectNode().put("a", 1))));

        assertAll(
                () -> assertTrue(db.connectionCalls.contains("rollback"), db.connectionCalls.toString()),
                () -> assertTrue(!db.connectionCalls.contains("commit"), db.connectionCalls.toString()));
    }

    // ── harness ───────────────────────────────────────────────────────────

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

    /** A connection that scripts the instance insert (and records any statement after it). */
    private static final class ScriptedDb {
        final List<String> sql = new ArrayList<>();
        final List<String> executions = new ArrayList<>();
        final List<String> connectionCalls = new ArrayList<>();
        @Nullable String writtenInputText;
        private @Nullable String returnedText;

        ScriptedDb returning(@Nullable String text) {
            this.returnedText = text;
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
                            yield statement(sql.size() == 1);
                        }
                        case "setAutoCommit", "commit", "rollback", "close" -> {
                            connectionCalls.add(m.getName());
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        private PreparedStatement statement(boolean instanceInsert) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setString" -> {
                                if (instanceInsert && (int) a[0] == 7) {
                                    writtenInputText = (String) a[1];
                                }
                                return null;
                            }
                            case "executeUpdate", "executeQuery" -> {
                                executions.add(m.getName());
                                return m.getName().equals("executeUpdate") ? 1 : resultSet();
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
            var remaining = new int[]{1};
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
