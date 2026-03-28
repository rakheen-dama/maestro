package io.b2mash.maestro.admin.repository;

import io.b2mash.maestro.admin.model.AdminWorkflow;
import io.b2mash.maestro.admin.model.Page;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for querying projected workflow state from the admin database.
 *
 * <p>Supports filtering by service, status, and free-text search across
 * workflow ID and workflow type. All queries use parameterized SQL to
 * prevent SQL injection.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. {@link JdbcTemplate} is thread-safe and
 * manages its own connection lifecycle.
 */
@Repository
public class WorkflowRepository {

    private static final RowMapper<AdminWorkflow> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;

    /**
     * Creates a new workflow repository.
     *
     * @param jdbc the JDBC template backed by the admin data source
     */
    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns a paginated list of workflows with optional filters.
     *
     * @param serviceFilter filter by service name, or {@code null} for all services
     * @param statusFilter  filter by workflow status, or {@code null} for all statuses
     * @param search        free-text search against workflow_id and workflow_type (case-insensitive),
     *                      or {@code null} for no search filter
     * @param offset        zero-based offset for pagination
     * @param limit         page size
     * @return a page of matching workflows ordered by updated_at descending
     */
    public Page<AdminWorkflow> findAll(
            @Nullable String serviceFilter,
            @Nullable String statusFilter,
            @Nullable String search,
            int offset,
            int limit
    ) {
        var where = new StringBuilder();
        var params = new ArrayList<>();

        appendFilter(where, params, "service_name = ?", serviceFilter);
        appendFilter(where, params, "status = ?", statusFilter);

        if (search != null && !search.isBlank()) {
            if (!where.isEmpty()) {
                where.append(" AND ");
            }
            where.append("(workflow_id ILIKE ? ESCAPE '\\' OR workflow_type ILIKE ? ESCAPE '\\')");
            var escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            var pattern = "%" + escaped + "%";
            params.add(pattern);
            params.add(pattern);
        }

        var whereClause = where.isEmpty() ? "" : " WHERE " + where;

        var countSql = "SELECT COUNT(*) FROM admin_workflow" + whereClause;
        var total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        var selectSql = "SELECT workflow_instance_id, workflow_id, workflow_type, service_name, "
                + "task_queue, status, last_step_name, started_at, completed_at, updated_at, event_count "
                + "FROM admin_workflow" + whereClause
                + " ORDER BY updated_at DESC LIMIT ? OFFSET ?";

        var selectParams = new ArrayList<>(params);
        selectParams.add(limit);
        selectParams.add(offset);

        var items = jdbc.query(selectSql, ROW_MAPPER, selectParams.toArray());

        return new Page<>(items, offset, limit, total != null ? total : 0L);
    }

    /**
     * Looks up a workflow by its business workflow ID.
     *
     * @param workflowId the business workflow ID
     * @return the workflow if found, empty otherwise
     */
    public Optional<AdminWorkflow> findByWorkflowId(String workflowId) {
        var results = jdbc.query(
                "SELECT workflow_instance_id, workflow_id, workflow_type, service_name, "
                        + "task_queue, status, last_step_name, started_at, completed_at, updated_at, event_count "
                        + "FROM admin_workflow WHERE workflow_id = ?",
                ROW_MAPPER,
                workflowId
        );
        return results.stream().findFirst();
    }

    /**
     * Returns a paginated list of failed workflows.
     *
     * @param offset zero-based offset for pagination
     * @param limit  page size
     * @return a page of failed workflows ordered by updated_at descending
     */
    public Page<AdminWorkflow> findFailed(int offset, int limit) {
        var total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_workflow WHERE status = 'FAILED'",
                Long.class
        );

        var items = jdbc.query(
                "SELECT workflow_instance_id, workflow_id, workflow_type, service_name, "
                        + "task_queue, status, last_step_name, started_at, completed_at, updated_at, event_count "
                        + "FROM admin_workflow WHERE status = 'FAILED' ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER,
                limit, offset
        );

        return new Page<>(items, offset, limit, total != null ? total : 0L);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static void appendFilter(StringBuilder where, List<Object> params,
                                     String clause, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            if (!where.isEmpty()) {
                where.append(" AND ");
            }
            where.append(clause);
            params.add(value);
        }
    }

    private static AdminWorkflow mapRow(ResultSet rs) throws SQLException {
        var completedTs = rs.getTimestamp("completed_at");
        return new AdminWorkflow(
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_id"),
                rs.getString("workflow_type"),
                rs.getString("service_name"),
                rs.getString("task_queue"),
                rs.getString("status"),
                rs.getString("last_step_name"),
                rs.getTimestamp("started_at").toInstant(),
                completedTs != null ? completedTs.toInstant() : null,
                rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("event_count")
        );
    }
}
