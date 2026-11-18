package io.b2mash.maestro.integration.upgrade.rolling;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * One fresh database per scenario, in one shared Postgres container, and the
 * raw-SQL reads the rolling-upgrade test asserts with.
 *
 * <p>The nodes create Maestro's schema themselves (the first node to start
 * migrates it, as in a deploy), so this class creates only the scenario's own
 * tables: {@code rolling_side_effect}, where every live activity execution
 * leaves a row, and {@code rolling_gate}, which holds the gated compensation.
 * Every assertion reads the tables directly, never through either build's
 * store, so neither build judges itself.
 *
 * <p><b>Thread safety:</b> one connection per call; safe from any thread.
 */
final class RollingDatabase {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rolling")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
    }

    private static final Duration POLL = Duration.ofMillis(200);

    /** A live activity execution, as {@code JdbcRollingActivities} recorded it. */
    record SideEffect(String activity, String phase, String node) {}

    /** One persisted event. */
    record Event(int sequence, String type, String step, String payload) {}

    private final String jdbcUrl;

    private RollingDatabase(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Creates an empty database and the scenario's own tables in it.
     *
     * @param name the database's name ({@code [a-z0-9_]+})
     * @return the database
     */
    static RollingDatabase create(String name) {
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("database name " + name);
        }
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create database " + name, e);
        }
        var url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
        var db = new RollingDatabase(url);
        db.execute("""
                CREATE TABLE rolling_side_effect (
                    id           BIGSERIAL PRIMARY KEY,
                    workflow_key TEXT NOT NULL,
                    activity     TEXT NOT NULL,
                    phase        TEXT NOT NULL,
                    node         TEXT NOT NULL,
                    at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())""");
        db.execute("CREATE TABLE rolling_gate (gate TEXT PRIMARY KEY)");
        return db;
    }

    /** @return the Spring datasource properties a node connects with */
    Map<String, String> datasourceProperties() {
        return Map.of(
                "spring.datasource.url", jdbcUrl,
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword());
    }

    /** @return the workflow's status, or empty if it has no instance row */
    Optional<String> status(String workflowId) {
        return queryOne("SELECT status FROM maestro_workflow_instance WHERE workflow_id = ?", workflowId);
    }

    /** @return the instance's lease epoch: every adoption raises it */
    long leaseEpoch(String workflowId) {
        return Long.parseLong(queryOne("SELECT lease_epoch FROM maestro_workflow_instance WHERE workflow_id = ?",
                workflowId).orElseThrow());
    }

    /** @return the output JSON as stored, or empty */
    Optional<String> output(String workflowId) {
        return queryOne("SELECT output::text FROM maestro_workflow_instance WHERE workflow_id = ?", workflowId);
    }

    /** @return every event of the workflow, in sequence order */
    List<Event> events(String workflowId) {
        var events = new ArrayList<Event>();
        try (var conn = connect();
             var ps = conn.prepareStatement("""
                     SELECT e.sequence_number, e.event_type, e.step_name, e.payload::text
                     FROM maestro_workflow_event e
                     JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id
                     WHERE i.workflow_id = ?
                     ORDER BY e.sequence_number""")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new Event(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return events;
    }

    /** @return every live activity execution recorded for the key, in order */
    List<SideEffect> sideEffects(String key) {
        var rows = new ArrayList<SideEffect>();
        try (var conn = connect();
             var ps = conn.prepareStatement(
                     "SELECT activity, phase, node FROM rolling_side_effect WHERE workflow_key = ? ORDER BY id")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SideEffect(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /**
     * Moves the workflow's pending timers to the database's {@code now()}, the
     * clock the timer poller reads ({@code fire_at <= now()}): the test's way
     * of letting an hour pass.
     *
     * @return the number of timers moved
     */
    int makePendingTimersDue(String workflowId) {
        try (var conn = connect();
             var ps = conn.prepareStatement("UPDATE maestro_workflow_timer SET fire_at = now() - interval '1 second' "
                     + "WHERE workflow_id = ? AND status = 'PENDING'")) {
            ps.setString(1, workflowId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens the gate a gated {@code refund} waits on. */
    void openGate(String key) {
        try (var conn = connect();
             var ps = conn.prepareStatement("INSERT INTO rolling_gate (gate) VALUES (?)")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Polls until {@code condition} holds.
     *
     * @throws AssertionError naming {@code what} and {@code diagnostics} on timeout
     */
    static void await(String what, Duration timeout, BooleanSupplier condition, java.util.function.Supplier<String> diagnostics) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, e);
            }
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for " + what + "\n" + diagnostics.get());
    }

    private Optional<String> queryOne(String sql, String arg) {
        try (var conn = connect();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            // Before the first node migrates, Maestro's tables do not exist yet.
            if ("42P01".equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql) {
        try (var conn = connect();
             var stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
