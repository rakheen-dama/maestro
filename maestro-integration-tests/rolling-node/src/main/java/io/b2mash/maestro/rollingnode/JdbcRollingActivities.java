package io.b2mash.maestro.rollingnode;

import io.b2mash.maestro.rollingnode.workflow.RollingActivities;
import io.b2mash.maestro.rollingnode.workflow.RollingBusinessFailure;
import io.b2mash.maestro.rollingnode.workflow.RollingSagaActivities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

/**
 * Both activity interfaces, recording every live execution in
 * {@code rolling_side_effect (workflow_key, activity, phase, node)} on the
 * host's pool. The test creates that table and {@code rolling_gate (gate)}
 * before any node starts. Rows are plain inserts, never upserts, so a step that
 * ran twice shows twice.
 *
 * <p><b>Thread safety:</b> stateless apart from the thread-safe
 * {@link JdbcTemplate}.
 */
@Component
public class JdbcRollingActivities implements RollingActivities, RollingSagaActivities {

    private static final Duration GATE_WAIT = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final String nodeName;

    /**
     * @param dataSource the host's {@code DataSource}
     * @param nodeName   {@code rolling.node-name}, recorded with every row
     */
    public JdbcRollingActivities(DataSource dataSource, @Value("${rolling.node-name}") String nodeName) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.nodeName = nodeName;
    }

    @Override
    public String step(String key, String step) {
        record(key, step, "done");
        return step;
    }

    @Override
    public String reserve(String key) {
        record(key, "reserve", "done");
        return key;
    }

    @Override
    public void release(String key) {
        record(key, "release", "done");
    }

    @Override
    public String charge(String key) {
        record(key, "charge", "done");
        return key;
    }

    @Override
    public void refund(String key) {
        record(key, "refund", "started");
        if (key.startsWith(GATED_PREFIX)) {
            awaitGate(key);
        }
        record(key, "refund", "done");
    }

    @Override
    public String ship(String key) {
        record(key, "ship", "done");
        throw new RollingBusinessFailure("ship refused for " + key);
    }

    private void record(String key, String activity, String phase) {
        jdbc.update("INSERT INTO rolling_side_effect (workflow_key, activity, phase, node) VALUES (?, ?, ?, ?)",
                key, activity, phase, nodeName);
    }

    private void awaitGate(String key) {
        var deadline = Instant.now().plus(GATE_WAIT);
        while (Instant.now().isBefore(deadline)) {
            Integer open = jdbc.queryForObject("SELECT count(*) FROM rolling_gate WHERE gate = ?", Integer.class, key);
            if (open != null && open > 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for gate " + key, e);
            }
        }
        throw new IllegalStateException("gate " + key + " not opened within " + GATE_WAIT);
    }
}
