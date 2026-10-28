package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.store.jdbc.AbstractJdbcWorkflowStore;
import io.b2mash.maestro.store.jdbc.JdbcStoreConfiguration;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PGobject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL implementation of the Maestro {@link io.b2mash.maestro.core.spi.WorkflowStore}.
 *
 * <p>Extends the abstract JDBC store with Postgres-specific optimizations:
 * <ul>
 *   <li>JSONB parameters via {@link PGobject} for native type handling.</li>
 *   <li>{@code FOR UPDATE SKIP LOCKED} for concurrent timer polling.</li>
 * </ul>
 *
 * <p>Flyway migrations for the schema are in
 * {@code classpath:db/maestro/store} and must be applied before using this
 * store — {@link io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer}
 * does so (the auto-configuration runs it at startup).
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe — all state is immutable after construction.
 *
 * @see AbstractJdbcWorkflowStore
 */
public final class PostgresWorkflowStore extends AbstractJdbcWorkflowStore {

    /**
     * Creates a Postgres workflow store with full configuration.
     *
     * @param dataSource the JDBC data source (typically a HikariCP pool)
     * @param config     store configuration (the ObjectMapper; table names are always
     *                   {@code maestro_*})
     */
    public PostgresWorkflowStore(DataSource dataSource, JdbcStoreConfiguration config) {
        super(dataSource, config);
    }

    /**
     * Creates a Postgres workflow store from a Jackson mapper alone. Tables are
     * always prefixed {@code "maestro_"}.
     *
     * @param dataSource   the JDBC data source
     * @param objectMapper Jackson 3 {@link ObjectMapper} for JSON serialization
     */
    public PostgresWorkflowStore(DataSource dataSource, ObjectMapper objectMapper) {
        super(dataSource, JdbcStoreConfiguration.withDefaults(objectMapper));
    }

    /**
     * Returns the timer polling SQL with Postgres {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>This ensures concurrent timer pollers process disjoint sets of timers
     * without contention — locked rows are silently skipped rather than
     * causing the query to block.
     */
    @Override
    protected String getDueTimersSql() {
        return "SELECT id, workflow_instance_id, workflow_id, timer_id, fire_at, "
                + "status, created_at FROM " + tableName("workflow_timer")
                + " WHERE fire_at <= ? AND status = 'PENDING'"
                + " ORDER BY fire_at ASC"
                + " LIMIT ?"
                + " FOR UPDATE SKIP LOCKED";
    }

    /**
     * Sets a JSON parameter using Postgres {@link PGobject} with type {@code jsonb}.
     *
     * <p>This ensures the PostgreSQL JDBC driver sends the correct wire-protocol
     * type, enabling native JSONB operations without implicit casts.
     */
    @Override
    protected void setJsonParameter(PreparedStatement ps, int index,
                                    @Nullable JsonNode node) throws SQLException {
        if (node == null || node.isNull()) {
            ps.setNull(index, Types.OTHER);
        } else {
            var pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(toJsonString(node));
            ps.setObject(index, pgObject);
        }
    }
}
