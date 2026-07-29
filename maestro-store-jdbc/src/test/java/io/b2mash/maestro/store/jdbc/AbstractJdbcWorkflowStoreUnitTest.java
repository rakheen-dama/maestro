package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.exception.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * with a hand-rolled JDBC fake would test the fake, not the store. See the
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
