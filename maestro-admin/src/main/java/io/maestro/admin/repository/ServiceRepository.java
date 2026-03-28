package io.maestro.admin.repository;

import io.maestro.admin.model.AdminService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Repository for querying discovered services from the admin database.
 *
 * <p>Services are auto-discovered from workflow lifecycle events — the
 * {@code service_name} field in each event populates the {@code admin_service}
 * table via the event projector.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. {@link JdbcTemplate} is thread-safe and
 * manages its own connection lifecycle.
 */
@Repository
public class ServiceRepository {

    private static final RowMapper<AdminService> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;

    /**
     * Creates a new service repository.
     *
     * @param jdbc the JDBC template backed by the admin data source
     */
    public ServiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns all discovered services ordered by name.
     *
     * @return list of all services, possibly empty
     */
    public List<AdminService> findAll() {
        return jdbc.query(
                "SELECT name, first_seen_at, last_seen_at FROM admin_service ORDER BY name",
                ROW_MAPPER
        );
    }

    /**
     * Looks up a single service by name.
     *
     * @param name the service name
     * @return the service if found, empty otherwise
     */
    public Optional<AdminService> findByName(String name) {
        var results = jdbc.query(
                "SELECT name, first_seen_at, last_seen_at FROM admin_service WHERE name = ?",
                ROW_MAPPER,
                name
        );
        return results.stream().findFirst();
    }

    private static AdminService mapRow(ResultSet rs) throws SQLException {
        return new AdminService(
                rs.getString("name"),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant()
        );
    }
}
