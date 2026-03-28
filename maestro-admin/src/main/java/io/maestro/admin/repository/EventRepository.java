package io.maestro.admin.repository;

import io.maestro.admin.model.AdminEvent;
import io.maestro.admin.model.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repository for querying lifecycle events from the admin database.
 *
 * <p>Events are append-only records projected from Kafka lifecycle events.
 * They form the workflow timeline displayed in the admin dashboard.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. {@link JdbcTemplate} is thread-safe and
 * manages its own connection lifecycle.
 */
@Repository
public class EventRepository {

    private static final RowMapper<AdminEvent> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;

    /**
     * Creates a new event repository.
     *
     * @param jdbc the JDBC template backed by the admin data source
     */
    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns all events for a workflow instance, ordered chronologically
     * (oldest first) to form the workflow timeline.
     *
     * @param instanceId the workflow instance UUID
     * @return list of events in chronological order, possibly empty
     */
    public List<AdminEvent> findByWorkflowInstanceId(UUID instanceId) {
        return jdbc.query(
                "SELECT id, workflow_instance_id, workflow_id, event_type, step_name, "
                        + "detail, event_timestamp, received_at "
                        + "FROM admin_event WHERE workflow_instance_id = ? "
                        + "ORDER BY event_timestamp ASC",
                ROW_MAPPER,
                instanceId
        );
    }

    /**
     * Returns a paginated list of events filtered by event types.
     *
     * <p>Useful for the signal monitor (event types: {@code SIGNAL_RECEIVED},
     * {@code SIGNAL_TIMEOUT}) and timer monitor (event types:
     * {@code TIMER_SCHEDULED}, {@code TIMER_FIRED}).
     *
     * @param eventTypes list of event type names to include
     * @param offset     zero-based offset for pagination
     * @param limit      page size
     * @return a page of matching events ordered by event_timestamp descending
     */
    public Page<AdminEvent> findByEventTypes(List<String> eventTypes, int offset, int limit) {
        if (eventTypes.isEmpty()) {
            return new Page<>(List.of(), offset, limit, 0L);
        }

        var placeholders = eventTypes.stream()
                .map(t -> "?")
                .collect(Collectors.joining(", "));

        var countSql = "SELECT COUNT(*) FROM admin_event WHERE event_type IN (" + placeholders + ")";
        var countParams = eventTypes.toArray();
        var total = jdbc.queryForObject(countSql, Long.class, countParams);

        var selectSql = "SELECT id, workflow_instance_id, workflow_id, event_type, step_name, "
                + "detail, event_timestamp, received_at "
                + "FROM admin_event WHERE event_type IN (" + placeholders + ") "
                + "ORDER BY event_timestamp DESC LIMIT ? OFFSET ?";

        var selectParams = new Object[eventTypes.size() + 2];
        for (int i = 0; i < eventTypes.size(); i++) {
            selectParams[i] = eventTypes.get(i);
        }
        selectParams[eventTypes.size()] = limit;
        selectParams[eventTypes.size() + 1] = offset;

        var items = jdbc.query(selectSql, ROW_MAPPER, selectParams);

        return new Page<>(items, offset, limit, total != null ? total : 0L);
    }

    private static AdminEvent mapRow(ResultSet rs) throws SQLException {
        return new AdminEvent(
                rs.getLong("id"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_id"),
                rs.getString("event_type"),
                rs.getString("step_name"),
                rs.getString("detail"),
                rs.getTimestamp("event_timestamp").toInstant(),
                rs.getTimestamp("received_at").toInstant()
        );
    }
}
