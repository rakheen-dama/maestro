package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 3.7 on a real Postgres: {@code getDueTimers(int)} selects the rows
 * the database's clock says are due ({@code fire_at <= now()}), whatever the
 * clock of the node that asks.
 *
 * <p>A Testcontainers database shares the host's clock, so a node's skew is
 * modelled two ways: a controllable {@link InstantSource} for the node side
 * (what a skewed old-build leader would bind to {@code getDueTimers(Instant,
 * int)}), and, for a database clock genuinely behind this JVM's, a statement
 * run inside a transaction opened earlier — {@code now()} is the transaction's
 * start time.
 */
class PostgresDatabaseClockTest extends PostgresTestSupport {

    private static final Duration SKEW = Duration.ofMinutes(5);

    @Test
    void dueTimersByTheStoreClockAreTheRowsTheDatabaseSaysAreDue() throws Exception {
        var dbNow = selectNow();
        var instance = store.createInstance(newInstance("clock-due"));
        var dueId = saveTimer(instance, "sleep-1", dbNow.minusSeconds(1));
        saveTimer(instance, "sleep-2", dbNow.plus(Duration.ofHours(1)));

        assertEquals(List.of(dueId), ids(store.getDueTimers(10)));
    }

    @Test
    void theDueTimerStatementComparesWithTheDatabaseClock() {
        assertTrue(store.getDueTimersByStoreClockSql().contains("WHERE fire_at <= now() AND status = 'PENDING'"),
                store.getDueTimersByStoreClockSql());
    }

    @Test
    void aNodeAheadOfTheDatabase_doesNotFireATimerTheDatabaseSaysIsNotDueYet() throws Exception {
        var dbNow = selectNow();
        var nodeClock = InstantSource.fixed(dbNow.plus(SKEW));
        var instance = store.createInstance(newInstance("clock-ahead"));
        var id = saveTimer(instance, "sleep-1", dbNow.plus(Duration.ofMinutes(1)));

        assertEquals(List.of(id), ids(store.getDueTimers(nodeClock.instant(), 10)),
                "on its own clock the node would fire it four minutes early");
        assertEquals(List.of(), ids(store.getDueTimers(10)), "by the database it is due in a minute");
    }

    @Test
    void aNodeBehindTheDatabase_firesATimerTheDatabaseSaysIsDue() throws Exception {
        var dbNow = selectNow();
        var nodeClock = InstantSource.fixed(dbNow.minus(SKEW));
        var instance = store.createInstance(newInstance("clock-behind"));
        var id = saveTimer(instance, "sleep-1", dbNow.minusSeconds(1));

        assertEquals(List.of(), ids(store.getDueTimers(nodeClock.instant(), 10)),
                "on its own clock the node would fire it five minutes late");
        assertEquals(List.of(id), ids(store.getDueTimers(10)), "by the database it is due");
    }

    /**
     * A database clock genuinely behind this JVM's: the statement runs in a
     * transaction opened before the timer fell due by the JVM, so its
     * {@code now()} is that transaction's start. The JVM's clock has passed
     * {@code fire_at}; the database's has not.
     */
    @Test
    void aDatabaseClockBehindThisJvm_holdsBackATimerThisJvmSaysIsDue() throws Exception {
        var instance = store.createInstance(newInstance("clock-lagging-db"));
        try (var lagging = dataSource.getConnection()) {
            lagging.setAutoCommit(false);
            var transactionNow = selectNow(lagging);
            var id = saveTimer(instance, "sleep-1", transactionNow.plusMillis(500));
            var jvmPastFireAt = transactionNow.plusMillis(1500);
            while (Instant.now().isBefore(jvmPastFireAt)) {
                Thread.sleep(50);
            }
            var laggingStore = new PostgresWorkflowStore(onlyConnection(lagging), objectMapper);
            try {
                assertEquals(List.of(), ids(laggingStore.getDueTimers(10)),
                        "the database's clock has not reached fire_at");
                assertEquals(List.of(id), ids(laggingStore.getDueTimers(Instant.now(), 10)),
                        "this JVM's clock has passed fire_at");
            } finally {
                lagging.rollback();
            }
        }
    }

    // ── Mixed-version fleets ───────────────────────────────────────────

    /**
     * An old-build leader polls {@code getDueTimers(its clock, n)}; a new-build
     * leader polls {@code getDueTimers(n)}. Leadership moves from an old
     * leader whose clock is ahead to a new one: the old leader fires the
     * not-yet-due timer early, and the new leader can neither see nor re-fire
     * either timer — firing is a compare-and-set on the row.
     */
    @Test
    void mixedFleet_anOldLeaderAheadThenANewLeader_firesEachTimerOnce() throws Exception {
        var dbNow = selectNow();
        var oldLeaderClock = InstantSource.fixed(dbNow.plus(SKEW));
        var instance = store.createInstance(newInstance("mixed-ahead"));
        var due = saveTimer(instance, "sleep-1", dbNow.minusSeconds(1));
        var notYetDue = saveTimer(instance, "sleep-2", dbNow.plus(Duration.ofMinutes(1)));

        var oldPoll = ids(store.getDueTimers(oldLeaderClock.instant(), 10));
        assertEquals(List.of(due, notYetDue), oldPoll, "the old leader's clock has passed both");
        assertTrue(store.markTimerFired(due));
        assertTrue(store.markTimerFired(notYetDue), "fired early, by the old leader's skew");

        assertEquals(List.of(), ids(store.getDueTimers(10)), "the new leader sees nothing left to fire");
        assertFalse(store.markTimerFired(due), "never fired twice");
        assertFalse(store.markTimerFired(notYetDue), "never fired twice");
    }

    /**
     * A new-build leader first, then an old one whose clock is behind: the new
     * leader fires the due timer once; the old leader fires the remaining one
     * only when its own clock reaches it (late, never lost) and cannot re-fire
     * the first.
     */
    @Test
    void mixedFleet_aNewLeaderThenAnOldLeaderBehind_firesEachTimerOnceAndLosesNone() throws Exception {
        var dbNow = selectNow();
        var instance = store.createInstance(newInstance("mixed-behind"));
        var dueNow = saveTimer(instance, "sleep-1", dbNow.minusSeconds(1));
        var dueSoon = saveTimer(instance, "sleep-2", dbNow.plus(Duration.ofMinutes(1)));

        assertEquals(List.of(dueNow), ids(store.getDueTimers(10)));
        assertTrue(store.markTimerFired(dueNow));

        var oldLeaderBehind = dbNow.minus(SKEW);
        assertEquals(List.of(), ids(store.getDueTimers(oldLeaderBehind, 10)),
                "the old leader neither re-fires the first nor sees the second yet");
        assertFalse(store.markTimerFired(dueNow), "never fired twice");

        var oldLeaderLater = dbNow.plus(Duration.ofMinutes(1)).plus(SKEW);
        assertEquals(List.of(dueSoon), ids(store.getDueTimers(oldLeaderLater, 10)),
                "once its own clock passes fire_at the old leader fires it: late, not lost");
        assertTrue(store.markTimerFired(dueSoon));
        assertEquals(List.of(), ids(store.getDueTimers(10)));
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Instant selectNow() throws SQLException {
        try (var conn = dataSource.getConnection()) {
            return selectNow(conn);
        }
    }

    private static Instant selectNow(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT now()");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    /** A data source that hands out {@code conn} and never closes it. */
    private static DataSource onlyConnection(Connection conn) {
        var unclosable = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("close")) {
                        return null;
                    }
                    try {
                        return m.invoke(conn, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> {
                    if (m.getName().equals("getConnection")) {
                        return unclosable;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private UUID saveTimer(WorkflowInstance instance, String timerId, Instant fireAt) {
        var timer = new WorkflowTimer(UUID.randomUUID(), instance.id(), instance.workflowId(), timerId,
                fireAt, TimerStatus.PENDING, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        store.saveTimer(timer);
        return timer.id();
    }

    private static List<UUID> ids(List<WorkflowTimer> timers) {
        return timers.stream().map(WorkflowTimer::id).toList();
    }

    private static WorkflowInstance newInstance(String workflowId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-workflow")
                .taskQueue("default")
                .status(WorkflowStatus.WAITING_TIMER)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }
}
