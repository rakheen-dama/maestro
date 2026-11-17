package io.b2mash.maestro.samples.perf.activity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * {@link PerfDbActivities} on the application's {@code DataSource} — the same
 * Hikari pool Maestro's store uses (sample-perf declares no
 * {@code @MaestroDataSource}): each call borrows one connection and runs
 * {@code SELECT pg_sleep(?)} on it, so the connection is busy, not idle, for
 * the whole hold. No {@code @Transactional}: the statement runs in
 * auto-commit and the connection goes back to the pool as soon as it ends.
 *
 * <p>Thread-safe: {@link JdbcTemplate} is.
 */
@Component
public class JdbcPerfDbActivities implements PerfDbActivities {

    private final JdbcTemplate jdbc;

    /**
     * Creates the activity on the application's pool.
     *
     * @param dataSource the application's {@code DataSource}
     */
    public JdbcPerfDbActivities(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public int hold(int index, long millis) {
        if (millis > 0) {
            jdbc.query("SELECT pg_sleep(?)", rs -> null, millis / 1000.0);
        }
        return index + 1;
    }
}
