package io.b2mash.maestro.store.jdbc;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Abstract JDBC implementation of {@link WorkflowStore}.
 *
 * <p>Provides a complete implementation using plain JDBC with parameterized
 * SQL. All JSON payloads are serialized/deserialized with Jackson
 * ({@code com.fasterxml.jackson}). Database-specific subclasses override protected
 * hook methods to provide optimized SQL where needed.
 *
 * <h2>Subclass Hooks</h2>
 * <ul>
 *   <li>{@link #getDueTimersSql()} — <b>abstract</b>, must provide row-level
 *       locking SQL (e.g., {@code FOR UPDATE SKIP LOCKED} in Postgres).</li>
 *   <li>{@link #setJsonParameter(PreparedStatement, int, JsonNode)} — override
 *       to use database-native JSON types (e.g., Postgres JSONB via PGobject).</li>
 *   <li>{@link #getJsonValue(ResultSet, String)} — override if the database
 *       returns JSON in a non-string format.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state is immutable after construction.
 * Connections are obtained per-operation from the injected {@link DataSource}.
 *
 * @see WorkflowStore
 * @see JdbcStoreConfiguration
 */
public abstract class AbstractJdbcWorkflowStore implements WorkflowStore {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcWorkflowStore.class);

    /** SQL state for unique constraint violation (standard across Postgres, H2, etc.). */
    private static final String UNIQUE_VIOLATION = "23505";

    /** SQL IN-clause fragment derived from {@link WorkflowStatus#isActive()}. */
    private static final String ACTIVE_STATUS_SQL = Arrays.stream(WorkflowStatus.values())
            .filter(WorkflowStatus::isActive)
            .map(s -> "'" + s.name() + "'")
            .collect(Collectors.joining(", "));

    private final DataSource dataSource;
    private final JdbcStoreConfiguration config;

    /**
     * Creates a new JDBC workflow store.
     *
     * @param dataSource the JDBC data source (expected to be a connection pool)
     * @param config     store configuration (table prefix, Jackson ObjectMapper)
     */
    protected AbstractJdbcWorkflowStore(DataSource dataSource, JdbcStoreConfiguration config) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
    }

    // ── Subclass hooks ────────────────────────────────────────────────────

    /**
     * Returns the SQL for polling due timers with row-level locking.
     *
     * <p>The SQL must:
     * <ol>
     *   <li>Select timers where {@code fire_at <= ?} and {@code status = 'PENDING'}.</li>
     *   <li>Order by {@code fire_at ASC}.</li>
     *   <li>Limit to {@code ?} rows.</li>
     *   <li>Apply row-level locking to prevent concurrent pollers from
     *       processing the same timer (e.g., {@code FOR UPDATE SKIP LOCKED}).</li>
     * </ol>
     *
     * @return the parameterized SQL string
     */
    protected abstract String getDueTimersSql();

    /**
     * Sets a JSON parameter on a {@link PreparedStatement}.
     *
     * <p>Default implementation uses {@link PreparedStatement#setString(int, String)}.
     * Override for database-native JSON types (e.g., Postgres JSONB via PGobject).
     *
     * @param ps    the prepared statement
     * @param index the parameter index (1-based)
     * @param node  the JSON value, may be {@code null}
     * @throws SQLException if a database access error occurs
     */
    protected void setJsonParameter(PreparedStatement ps, int index,
                                    @Nullable JsonNode node) throws SQLException {
        if (node == null || node.isNull()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, toJsonString(node));
        }
    }

    /**
     * Reads a JSON value from a {@link ResultSet} column.
     *
     * <p>Default implementation reads a string and parses it with Jackson.
     * Override if the database returns JSON in a non-string format.
     *
     * @param rs     the result set
     * @param column the column name
     * @return the parsed {@link JsonNode}, or {@code null} if the column is SQL NULL
     * @throws SQLException if a database access error occurs
     */
    protected @Nullable JsonNode getJsonValue(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        return json == null ? null : parseJsonNode(json);
    }

    // ── Protected utilities ───────────────────────────────────────────────

    /**
     * Returns the fully qualified table name with the configured prefix.
     *
     * @param suffix the table name suffix (e.g., {@code "workflow_instance"})
     * @return the prefixed table name (e.g., {@code "maestro_workflow_instance"})
     */
    protected final String tableName(String suffix) {
        return config.tablePrefix() + suffix;
    }

    /**
     * Returns the configured Jackson {@link ObjectMapper}.
     */
    protected final ObjectMapper objectMapper() {
        return config.objectMapper();
    }

    // ── Instance operations ───────────────────────────────────────────────

    @Override
    public WorkflowInstance createInstance(WorkflowInstance instance) {
        Objects.requireNonNull(instance, "instance");
        log.debug("Creating workflow instance: workflowId={}", instance.workflowId());

        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertInstance(conn, instance);
                doAdoptOrphanedSignals(conn, instance.workflowId(), instance.id());
                conn.commit();
                log.debug("Created workflow instance: workflowId={}, id={}",
                        instance.workflowId(), instance.id());
                return instance;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                var ex = new WorkflowAlreadyExistsException(instance.workflowId());
                ex.initCause(e);
                throw ex;
            }
            throw new UncheckedSqlException("Failed to create workflow instance: "
                    + instance.workflowId(), e);
        }
    }

    @Override
    public Optional<WorkflowInstance> getInstance(String workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");

        String sql = "SELECT id, workflow_id, run_id, workflow_type, task_queue, status, "
                + "input, output, service_name, event_sequence, started_at, completed_at, "
                + "updated_at, version FROM " + tableName("workflow_instance")
                + " WHERE workflow_id = ?";

        return querySingle(sql, ps -> ps.setString(1, workflowId), this::mapInstance);
    }

    @Override
    public List<WorkflowInstance> getRecoverableInstances() {
        String sql = "SELECT id, workflow_id, run_id, workflow_type, task_queue, status, "
                + "input, output, service_name, event_sequence, started_at, completed_at, "
                + "updated_at, version FROM " + tableName("workflow_instance")
                + " WHERE status IN (" + ACTIVE_STATUS_SQL + ")"
                + " ORDER BY started_at ASC";

        return queryList(sql, ps -> {}, this::mapInstance);
    }

    @Override
    public void updateInstance(WorkflowInstance instance) {
        Objects.requireNonNull(instance, "instance");

        String sql = "UPDATE " + tableName("workflow_instance")
                + " SET run_id = ?, workflow_type = ?, task_queue = ?, status = ?, "
                + "input = ?, output = ?, service_name = ?, event_sequence = ?, "
                + "started_at = ?, completed_at = ?, updated_at = ?, version = ? + 1"
                + " WHERE id = ? AND version = ?";

        int updated = update(sql, ps -> {
            ps.setObject(1, instance.runId());
            ps.setString(2, instance.workflowType());
            ps.setString(3, instance.taskQueue());
            ps.setString(4, instance.status().name());
            setJsonParameter(ps, 5, instance.input());
            setJsonParameter(ps, 6, instance.output());
            ps.setString(7, instance.serviceName());
            ps.setInt(8, instance.eventSequence());
            ps.setTimestamp(9, Timestamp.from(instance.startedAt()));
            setNullableTimestamp(ps, 10, instance.completedAt());
            ps.setTimestamp(11, Timestamp.from(instance.updatedAt()));
            ps.setInt(12, instance.version());
            ps.setObject(13, instance.id());
            ps.setInt(14, instance.version());
        });

        if (updated == 0) {
            resolveUpdateFailure(instance);
        }

        log.debug("Updated workflow instance: workflowId={}, version={}→{}",
                instance.workflowId(), instance.version(), instance.version() + 1);
    }

    // ── Event operations ──────────────────────────────────────────────────

    @Override
    public void appendEvent(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");

        String sql = "INSERT INTO " + tableName("workflow_event")
                + " (id, workflow_instance_id, sequence_number, event_type, step_name,"
                + " payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try {
            update(sql, ps -> {
                ps.setObject(1, event.id());
                ps.setObject(2, event.workflowInstanceId());
                ps.setInt(3, event.sequenceNumber());
                ps.setString(4, event.eventType().name());
                setNullableString(ps, 5, event.stepName());
                setJsonParameter(ps, 6, event.payload());
                ps.setTimestamp(7, Timestamp.from(event.createdAt()));
            });
        } catch (UncheckedSqlException e) {
            if (e.getCause() instanceof SQLException sqle
                    && UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
                var ex = new DuplicateEventException(event.workflowInstanceId(),
                        event.sequenceNumber());
                ex.initCause(sqle);
                throw ex;
            }
            throw e;
        }
    }

    @Override
    public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
        Objects.requireNonNull(instanceId, "instanceId");

        String sql = "SELECT id, workflow_instance_id, sequence_number, event_type, "
                + "step_name, payload, created_at FROM " + tableName("workflow_event")
                + " WHERE workflow_instance_id = ? AND sequence_number = ?";

        return querySingle(sql, ps -> {
            ps.setObject(1, instanceId);
            ps.setInt(2, sequenceNumber);
        }, this::mapEvent);
    }

    @Override
    public List<WorkflowEvent> getEvents(UUID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");

        String sql = "SELECT id, workflow_instance_id, sequence_number, event_type, "
                + "step_name, payload, created_at FROM " + tableName("workflow_event")
                + " WHERE workflow_instance_id = ? ORDER BY sequence_number ASC";

        return queryList(sql, ps -> ps.setObject(1, instanceId), this::mapEvent);
    }

    // ── Signal operations ─────────────────────────────────────────────────

    @Override
    public void saveSignal(WorkflowSignal signal) {
        Objects.requireNonNull(signal, "signal");

        String sql = "INSERT INTO " + tableName("workflow_signal")
                + " (id, workflow_instance_id, workflow_id, signal_name, payload,"
                + " consumed, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

        update(sql, ps -> {
            ps.setObject(1, signal.id());
            setNullableUuid(ps, 2, signal.workflowInstanceId());
            ps.setString(3, signal.workflowId());
            ps.setString(4, signal.signalName());
            setJsonParameter(ps, 5, signal.payload());
            ps.setBoolean(6, signal.consumed());
            ps.setTimestamp(7, Timestamp.from(signal.receivedAt()));
        });
    }

    @Override
    public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(signalName, "signalName");

        String sql = "SELECT id, workflow_instance_id, workflow_id, signal_name, "
                + "payload, consumed, received_at FROM " + tableName("workflow_signal")
                + " WHERE workflow_id = ? AND signal_name = ? AND consumed = false"
                + " ORDER BY received_at ASC";

        return queryList(sql, ps -> {
            ps.setString(1, workflowId);
            ps.setString(2, signalName);
        }, this::mapSignal);
    }

    @Override
    public void markSignalConsumed(UUID signalId) {
        Objects.requireNonNull(signalId, "signalId");

        String sql = "UPDATE " + tableName("workflow_signal")
                + " SET consumed = true WHERE id = ?";

        update(sql, ps -> ps.setObject(1, signalId));
    }

    @Override
    public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(instanceId, "instanceId");

        try (var conn = dataSource.getConnection()) {
            doAdoptOrphanedSignals(conn, workflowId, instanceId);
        } catch (SQLException e) {
            throw new UncheckedSqlException("Failed to adopt orphaned signals for workflow: "
                    + workflowId, e);
        }
    }

    // ── Timer operations ──────────────────────────────────────────────────

    @Override
    public void saveTimer(WorkflowTimer timer) {
        Objects.requireNonNull(timer, "timer");

        String sql = "INSERT INTO " + tableName("workflow_timer")
                + " (id, workflow_instance_id, workflow_id, timer_id, fire_at, status,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

        update(sql, ps -> {
            ps.setObject(1, timer.id());
            ps.setObject(2, timer.workflowInstanceId());
            ps.setString(3, timer.workflowId());
            ps.setString(4, timer.timerId());
            ps.setTimestamp(5, Timestamp.from(timer.fireAt()));
            ps.setString(6, timer.status().name());
            ps.setTimestamp(7, Timestamp.from(timer.createdAt()));
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Lock lifetime note:</b> Row locks from {@code FOR UPDATE SKIP LOCKED}
     * are released when the connection returns to the pool (auto-commit). This
     * means the SKIP LOCKED clause prevents <em>within-query</em> contention but
     * does not hold locks across the {@code getDueTimers} → {@code markTimerFired}
     * boundary. The {@link #markTimerFired(UUID)} compare-and-set
     * ({@code WHERE status = 'PENDING'}) is the authoritative concurrency guard.
     * Since Maestro uses a leader-elected timer poller, only one poller is active
     * at a time, making the auto-commit lock lifetime acceptable.
     */
    @Override
    public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
        Objects.requireNonNull(now, "now");

        return queryList(getDueTimersSql(), ps -> {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setInt(2, batchSize);
        }, this::mapTimer);
    }

    @Override
    public boolean markTimerFired(UUID timerId) {
        Objects.requireNonNull(timerId, "timerId");

        String sql = "UPDATE " + tableName("workflow_timer")
                + " SET status = 'FIRED' WHERE id = ? AND status = 'PENDING'";

        return update(sql, ps -> ps.setObject(1, timerId)) > 0;
    }

    @Override
    public void markTimerCancelled(UUID timerId) {
        Objects.requireNonNull(timerId, "timerId");

        String sql = "UPDATE " + tableName("workflow_timer")
                + " SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'";

        update(sql, ps -> ps.setObject(1, timerId));
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private void insertInstance(Connection conn, WorkflowInstance instance) throws SQLException {
        String sql = "INSERT INTO " + tableName("workflow_instance")
                + " (id, workflow_id, run_id, workflow_type, task_queue, status, "
                + "input, output, service_name, event_sequence, started_at, completed_at, "
                + "updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, instance.id());
            ps.setString(2, instance.workflowId());
            ps.setObject(3, instance.runId());
            ps.setString(4, instance.workflowType());
            ps.setString(5, instance.taskQueue());
            ps.setString(6, instance.status().name());
            setJsonParameter(ps, 7, instance.input());
            setJsonParameter(ps, 8, instance.output());
            ps.setString(9, instance.serviceName());
            ps.setInt(10, instance.eventSequence());
            ps.setTimestamp(11, Timestamp.from(instance.startedAt()));
            setNullableTimestamp(ps, 12, instance.completedAt());
            ps.setTimestamp(13, Timestamp.from(instance.updatedAt()));
            ps.setInt(14, instance.version());
            ps.executeUpdate();
        }
    }

    private void doAdoptOrphanedSignals(Connection conn, String workflowId,
                                         UUID instanceId) throws SQLException {
        String sql = "UPDATE " + tableName("workflow_signal")
                + " SET workflow_instance_id = ? WHERE workflow_id = ?"
                + " AND workflow_instance_id IS NULL";

        try (var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, instanceId);
            ps.setString(2, workflowId);
            int adopted = ps.executeUpdate();
            if (adopted > 0) {
                log.debug("Adopted {} orphaned signal(s) for workflowId={}",
                        adopted, workflowId);
            }
        }
    }

    /**
     * Determines whether an update failure was caused by a missing workflow
     * or a version conflict, and throws the appropriate exception.
     */
    private void resolveUpdateFailure(WorkflowInstance instance) {
        String sql = "SELECT version FROM " + tableName("workflow_instance")
                + " WHERE id = ?";

        Optional<Integer> currentVersion = querySingle(sql,
                ps -> ps.setObject(1, instance.id()),
                rs -> rs.getInt("version"));

        if (currentVersion.isEmpty()) {
            throw new WorkflowNotFoundException(instance.workflowId());
        }
        throw new OptimisticLockException(instance.workflowId(),
                instance.version(), currentVersion.get());
    }

    // ── Row mappers ───────────────────────────────────────────────────────

    private WorkflowInstance mapInstance(ResultSet rs) throws SQLException {
        return WorkflowInstance.builder()
                .id(rs.getObject("id", UUID.class))
                .workflowId(rs.getString("workflow_id"))
                .runId(rs.getObject("run_id", UUID.class))
                .workflowType(rs.getString("workflow_type"))
                .taskQueue(rs.getString("task_queue"))
                .status(WorkflowStatus.valueOf(rs.getString("status")))
                .input(getJsonValue(rs, "input"))
                .output(getJsonValue(rs, "output"))
                .serviceName(rs.getString("service_name"))
                .eventSequence(rs.getInt("event_sequence"))
                .startedAt(rs.getTimestamp("started_at").toInstant())
                .completedAt(getNullableInstant(rs, "completed_at"))
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .version(rs.getInt("version"))
                .build();
    }

    private WorkflowEvent mapEvent(ResultSet rs) throws SQLException {
        return new WorkflowEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getInt("sequence_number"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("step_name"),
                getJsonValue(rs, "payload"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private WorkflowSignal mapSignal(ResultSet rs) throws SQLException {
        return new WorkflowSignal(
                rs.getObject("id", UUID.class),
                getNullableUuid(rs, "workflow_instance_id"),
                rs.getString("workflow_id"),
                rs.getString("signal_name"),
                getJsonValue(rs, "payload"),
                rs.getBoolean("consumed"),
                rs.getTimestamp("received_at").toInstant()
        );
    }

    private WorkflowTimer mapTimer(ResultSet rs) throws SQLException {
        return new WorkflowTimer(
                rs.getObject("id", UUID.class),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_id"),
                rs.getString("timer_id"),
                rs.getTimestamp("fire_at").toInstant(),
                TimerStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    // ── JDBC template methods ─────────────────────────────────────────────

    private <T> Optional<T> querySingle(String sql, StatementSetter setter,
                                         RowMapper<T> mapper) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            setter.set(ps);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapper.map(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new UncheckedSqlException("Query failed: " + sql, e);
        }
    }

    private <T> List<T> queryList(String sql, StatementSetter setter,
                                   RowMapper<T> mapper) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            setter.set(ps);
            try (var rs = ps.executeQuery()) {
                var results = new ArrayList<T>();
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new UncheckedSqlException("Query failed: " + sql, e);
        }
    }

    private int update(String sql, StatementSetter setter) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            setter.set(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedSqlException("Update failed: " + sql, e);
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    protected String toJsonString(JsonNode node) {
        try {
            return objectMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize JsonNode", e);
        }
    }

    private JsonNode parseJsonNode(String json) {
        try {
            return objectMapper().readTree(json);
        } catch (Exception e) {
            throw new SerializationException("Failed to parse JSON", e);
        }
    }

    // ── Parameter helpers ─────────────────────────────────────────────────

    private static void setNullableTimestamp(PreparedStatement ps, int index,
                                              @Nullable Instant instant) throws SQLException {
        if (instant == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private static void setNullableString(PreparedStatement ps, int index,
                                           @Nullable String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableUuid(PreparedStatement ps, int index,
                                         @Nullable UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }

    private static @Nullable Instant getNullableInstant(ResultSet rs,
                                                         String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }

    private static @Nullable UUID getNullableUuid(ResultSet rs,
                                                    String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    // ── Functional interfaces ─────────────────────────────────────────────

    @FunctionalInterface
    interface StatementSetter {
        void set(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    // ── Exception wrapper ─────────────────────────────────────────────────

    /**
     * Unchecked wrapper for {@link SQLException} thrown by infrastructure
     * operations. This is NOT a {@link io.b2mash.maestro.core.exception.MaestroException}
     * because SQL failures are infrastructure errors, not domain errors.
     */
    static final class UncheckedSqlException extends RuntimeException {
        UncheckedSqlException(String message, SQLException cause) {
            super(message, cause);
        }
    }
}
