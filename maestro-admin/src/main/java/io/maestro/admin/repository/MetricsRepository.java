package io.maestro.admin.repository;

import io.maestro.admin.model.AdminMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository for querying pre-computed workflow metrics from the admin database.
 *
 * <p>Metrics are updated transactionally by the event projector and represent
 * workflow counts grouped by service name and status.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. {@link JdbcTemplate} is thread-safe and
 * manages its own connection lifecycle.
 */
@Repository
public class MetricsRepository {

    private static final RowMapper<AdminMetrics> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;

    /**
     * Creates a new metrics repository.
     *
     * @param jdbc the JDBC template backed by the admin data source
     */
    public MetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns all metrics rows ordered by service name and status.
     *
     * @return list of all metrics, possibly empty
     */
    public List<AdminMetrics> findAll() {
        return jdbc.query(
                "SELECT service_name, status, workflow_count, last_updated_at "
                        + "FROM admin_metrics ORDER BY service_name, status",
                ROW_MAPPER
        );
    }

    /**
     * Returns an overview of workflow counts grouped by service name and then by status.
     *
     * <p>The outer map is keyed by service name, the inner map by workflow status,
     * with the value being the workflow count. Both maps preserve insertion order
     * (service names and statuses are sorted alphabetically).
     *
     * @return nested map of service name to (status to count), possibly empty
     */
    public Map<String, Map<String, Long>> getOverview() {
        var result = new LinkedHashMap<String, Map<String, Long>>();

        jdbc.query(
                "SELECT service_name, status, workflow_count "
                        + "FROM admin_metrics ORDER BY service_name, status",
                (rs) -> {
                    var serviceName = rs.getString("service_name");
                    var status = rs.getString("status");
                    var count = rs.getLong("workflow_count");

                    result.computeIfAbsent(serviceName, k -> new LinkedHashMap<>())
                            .put(status, count);
                }
        );

        return result;
    }

    private static AdminMetrics mapRow(ResultSet rs) throws SQLException {
        return new AdminMetrics(
                rs.getString("service_name"),
                rs.getString("status"),
                rs.getLong("workflow_count"),
                rs.getTimestamp("last_updated_at").toInstant()
        );
    }
}
