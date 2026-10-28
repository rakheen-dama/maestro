package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the parts of {@link AbstractJdbcWorkflowStore} that don't
 * require a live database: constructor validation, {@code tableName()}
 * prefixing, and the JSON-serialization helper that
 * {@code appendEvent}/{@code createInstance}/{@code updateInstance} all
 * funnel through.
 *
 * <p>Every other method on this class executes SQL and is exercised through
 * a live PostgreSQL backend by {@code maestro-store-postgres}'s Testcontainers
 * suite (the concrete subclass under test); duplicating that coverage here
 * with a hand-rolled JDBC fake would test the fake, not the store. The one
 * exception is {@link FailedRollbackTests}: a rollback that itself fails
 * cannot be provoked on a live database on demand, so that path is scripted
 * with a proxy {@link Connection}. See the
 * class Javadoc on {@link AbstractJdbcWorkflowStore} for the subclass-hook
 * contract this store delegates dialect-specific SQL to.
 */
@DisplayName("AbstractJdbcWorkflowStore (database-free logic)")
class AbstractJdbcWorkflowStoreUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final DataSource neverUsedDataSource = new NeverUsedDataSource();

    @Test
    @DisplayName("rejects a null DataSource")
    void constructor_rejectsNullDataSource() {
        var config = JdbcStoreConfiguration.withDefaults(mapper);

        var ex = assertThrows(NullPointerException.class,
                () -> new TestStore(null, config));
        assertEquals("dataSource", ex.getMessage());
    }

    @Test
    @DisplayName("rejects a null configuration")
    void constructor_rejectsNullConfig() {
        var ex = assertThrows(NullPointerException.class,
                () -> new TestStore(neverUsedDataSource, null));
        assertEquals("config", ex.getMessage());
    }

    @Test
    @DisplayName("tableName() prefixes with the configured table prefix")
    void tableName_usesConfiguredPrefix() {
        var store = new TestStore(neverUsedDataSource,
                new JdbcStoreConfiguration("custom_", mapper));

        assertEquals("custom_workflow_instance", store.tableName("workflow_instance"));
    }

    @Test
    @DisplayName("tableName() defaults to the 'maestro_' prefix")
    void tableName_defaultsToMaestroPrefix() {
        var store = new TestStore(neverUsedDataSource, JdbcStoreConfiguration.withDefaults(mapper));

        assertEquals("maestro_workflow_event", store.tableName("workflow_event"));
    }

    @Test
    @DisplayName("objectMapper() returns the exact instance passed in the configuration")
    void objectMapper_returnsConfiguredInstance() {
        var store = new TestStore(neverUsedDataSource, JdbcStoreConfiguration.withDefaults(mapper));

        assertSame(mapper, store.objectMapper());
    }

    @Test
    @DisplayName("toJsonString() serializes a JsonNode with the configured mapper")
    void toJsonString_serializesNode() {
        var store = new TestStore(neverUsedDataSource, JdbcStoreConfiguration.withDefaults(mapper));
        JsonNode node = JsonNodeFactory.instance.objectNode().put("key", "value");

        assertEquals("{\"key\":\"value\"}", store.toJsonString(node));
    }

    @Test
    @DisplayName("toJsonString() wraps a serialization failure as SerializationException")
    void toJsonString_wrapsFailureAsSerializationException() {
        var throwingMapper = new ThrowingObjectMapper();
        var store = new TestStore(neverUsedDataSource,
                new JdbcStoreConfiguration("maestro_", throwingMapper));
        JsonNode node = JsonNodeFactory.instance.objectNode().put("key", "value");

        var ex = assertThrows(SerializationException.class, () -> store.toJsonString(node));
        assertEquals("forced serialization failure", ex.getCause().getMessage());
    }

    // ── R34: a failed rollback must never be followed by setAutoCommit(true) ──

    /**
     * After a statement fails and the {@code rollback()} that follows also
     * fails, the transaction may still be open on a live connection. JDBC
     * specifies that {@code setAutoCommit(true)} mid-transaction COMMITS it,
     * so restoring auto-commit there would commit the half-done write. The
     * store must abort the connection instead. Scripted with a
     * {@link Proxy}-based {@link Connection}: no database involved.
     */
    @Nested
    @DisplayName("a failed rollback abandons the connection instead of restoring auto-commit (R34)")
    class FailedRollbackTests {

        @Test
        @DisplayName("createInstance: statement fails, rollback fails → abort, never setAutoCommit(true)")
        void createInstance_failedRollback_abortsInsteadOfRestoringAutoCommit() {
            var conn = new ScriptedConnection(true);
            var store = new TestStore(new SingleConnectionDataSource(conn.proxy()),
                    JdbcStoreConfiguration.withDefaults(mapper));

            var ex = assertThrows(AbstractJdbcWorkflowStore.UncheckedSqlException.class, () -> store.createInstance(instance()));

            assertEquals("statement failed", ex.getCause().getMessage());
            assertEquals("rollback failed", ex.getCause().getSuppressed()[0].getMessage(),
                    "the rollback failure is attached to the real cause, not substituted for it");
            assertFalse(conn.calls.contains("setAutoCommit(true)"),
                    "setAutoCommit(true) after a failed rollback would COMMIT the open transaction; calls were "
                            + conn.calls);
            assertTrue(conn.calls.contains("abort"), "the connection must be aborted; calls were " + conn.calls);
        }

        @Test
        @DisplayName("completeInstance: statement fails, rollback fails → abort, never setAutoCommit(true)")
        void completeInstance_failedRollback_abortsInsteadOfRestoringAutoCommit() {
            var conn = new ScriptedConnection(true);
            var store = new TestStore(new SingleConnectionDataSource(conn.proxy()),
                    JdbcStoreConfiguration.withDefaults(mapper));
            var terminal = instance().toBuilder().status(WorkflowStatus.COMPLETED).version(1).build();
            var event = new WorkflowEvent(UUID.randomUUID(), terminal.id(), 1, EventType.WORKFLOW_COMPLETED,
                    null, null, Instant.now());

            assertThrows(AbstractJdbcWorkflowStore.UncheckedSqlException.class, () -> store.completeInstance(terminal, event));

            assertFalse(conn.calls.contains("setAutoCommit(true)"),
                    "setAutoCommit(true) after a failed rollback would COMMIT the open transaction; calls were "
                            + conn.calls);
            assertTrue(conn.calls.contains("abort"), "the connection must be aborted; calls were " + conn.calls);
        }

        @Test
        @DisplayName("control: statement fails, rollback succeeds → auto-commit restored, no abort")
        void createInstance_successfulRollback_restoresAutoCommit() {
            var conn = new ScriptedConnection(false);
            var store = new TestStore(new SingleConnectionDataSource(conn.proxy()),
                    JdbcStoreConfiguration.withDefaults(mapper));

            assertThrows(AbstractJdbcWorkflowStore.UncheckedSqlException.class, () -> store.createInstance(instance()));

            assertTrue(conn.calls.contains("setAutoCommit(true)"), "calls were " + conn.calls);
            assertFalse(conn.calls.contains("abort"), "calls were " + conn.calls);
        }

        private WorkflowInstance instance() {
            var now = Instant.now();
            return WorkflowInstance.builder()
                    .id(UUID.randomUUID())
                    .workflowId("wf-r34")
                    .runId(UUID.randomUUID())
                    .workflowType("test-workflow")
                    .taskQueue("default")
                    .status(WorkflowStatus.RUNNING)
                    .serviceName("test-service")
                    .eventSequence(0)
                    .startedAt(now)
                    .updatedAt(now)
                    .version(0)
                    .build();
        }
    }

    /**
     * A {@link Connection} whose every statement fails on execution and whose
     * {@code rollback()} fails when {@code rollbackFails}; it records the
     * transaction-control calls it receives.
     */
    private static final class ScriptedConnection {

        final List<String> calls = new ArrayList<>();
        private final boolean rollbackFails;

        ScriptedConnection(boolean rollbackFails) {
            this.rollbackFails = rollbackFails;
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (p, method, args) -> switch (method.getName()) {
                        case "setAutoCommit" -> {
                            calls.add("setAutoCommit(" + args[0] + ")");
                            yield null;
                        }
                        case "rollback" -> {
                            calls.add("rollback");
                            if (rollbackFails) {
                                throw new SQLException("rollback failed", "08006");
                            }
                            yield null;
                        }
                        case "commit", "abort", "close" -> {
                            calls.add(method.getName());
                            yield null;
                        }
                        case "prepareStatement" -> failingStatement();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private static PreparedStatement failingStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (p, method, args) -> switch (method.getName()) {
                        case "executeUpdate", "executeQuery", "execute" ->
                                throw new SQLException("statement failed", "XX000");
                        case "close" -> null;
                        default -> method.getName().startsWith("set") ? null
                                : unsupported(method.getName());
                    });
        }

        private static Object unsupported(String name) {
            throw new UnsupportedOperationException(name);
        }
    }

    /** Hands out one scripted connection; everything else is unused. */
    private static final class SingleConnectionDataSource implements DataSource {
        private final Connection connection;

        SingleConnectionDataSource(Connection connection) {
            this.connection = connection;
        }

        @Override public Connection getConnection() { return connection; }
        @Override public Connection getConnection(String username, String password) { return connection; }
        @Override public java.io.PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(java.io.PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unwrap"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** Minimal concrete subclass — the abstract hook is never exercised by these tests. */
    private static final class TestStore extends AbstractJdbcWorkflowStore {
        TestStore(DataSource dataSource, JdbcStoreConfiguration config) {
            super(dataSource, config);
        }

        @Override
        protected String getDueTimersSql() {
            throw new UnsupportedOperationException("not exercised by unit tests");
        }
    }

    /** ObjectMapper stand-in that always fails serialization, to exercise the catch path. */
    @SuppressWarnings("unchecked")
    private static final class ThrowingObjectMapper extends JsonMapper {
        @Override
        public String writeValueAsString(Object value) {
            throw new RuntimeException("forced serialization failure");
        }
    }

    /**
     * A {@link DataSource} whose methods are never expected to be called by
     * the logic under test; each throws if that assumption is ever violated.
     */
    private static final class NeverUsedDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("not exercised by these unit tests");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("not exercised by these unit tests");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("not exercised by these unit tests");
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
            throw new UnsupportedOperationException("not exercised by these unit tests");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("not exercised by these unit tests");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("not exercised by these unit tests");
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("not exercised by these unit tests");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not exercised by these unit tests");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
