package io.b2mash.maestro.integration.upgrade.cbf5a2a;

import io.b2mash.maestro.core.engine.WorkflowExecutor.TerminateOutcome;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.TerminalWait;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Approval;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.FailingRequest;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Invocations;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.OrderRequest;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedFailingActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedFailingWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedOrderActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedOrderWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedRiskActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedSagaActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedSagaWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedResultWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.StepGate;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.TypedRequest;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture driver that wrote {@code upgrade/cbf5a2a/seeded-history.sql}.
 * It runs only when {@code MAESTRO_UPGRADE_CAPTURE_OUT} is set, which only
 * {@code scripts/capture-upgrade-fixture.sh history <ref> <name>} does —
 * inside a detached worktree of the capture ref, so the engine, store and
 * migrations that write the history are that ref's, not this build's.
 *
 * <p>It drives every workflow of the {@link Seeds} catalogue to its status at
 * capture on two nodes with no lock backend (a crashed JVM's locks would have
 * expired anyway, and none are left to dump), asserts the catalogue holds,
 * then {@code pg_dump}s every {@code maestro_*} table and writes
 * {@code capture.properties} next to the dump.
 *
 * <p>It may use only APIs present at {@code cbf5a2a}: {@link MaestroEngineHarness},
 * {@link PostgresWorkflowStore}, {@link MaestroSchemaInitializer},
 * {@link TerminalWait}, Testcontainers and {@link JsonMapper}. It never
 * constructs a {@code WorkflowExecutor} itself — the harness does, in
 * whichever form its build supports.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "MAESTRO_UPGRADE_CAPTURE_OUT", matches = ".+")
class SeededHistoryCapture {

    /** Path of the frozen fixture source, relative to the module directory (Gradle's working directory). */
    static final String FIXTURE_SOURCE =
            "src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/SeededHistoryWorkflows.java";

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Approval CAPTURE_APPROVAL = new Approval("capture", 1);

    @Test
    void captureSeededHistory() throws Exception {
        var out = Path.of(System.getenv("MAESTRO_UPGRADE_CAPTURE_OUT"));
        var ref = System.getenv("MAESTRO_UPGRADE_CAPTURE_REF");
        assertTrue(ref != null && ref.matches("[0-9a-f]{40}"),
                "MAESTRO_UPGRADE_CAPTURE_REF must be a full commit SHA, was: " + ref);
        Files.createDirectories(out);

        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("maestro_seed")
                .withUsername("test")
                .withPassword("test")) {
            postgres.start();
            var ds = new PGSimpleDataSource();
            ds.setUrl(postgres.getJdbcUrl());
            ds.setUser(postgres.getUsername());
            ds.setPassword(postgres.getPassword());

            new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, getClass().getClassLoader()).initialize();

            var mapper = JsonMapper.builder().build();
            var store = new PostgresWorkflowStore(ds, mapper);
            var gates = new Gates();
            gates.hold("seed-order.charge", Seeds.CRASHED);
            gates.hold("seed-saga.releaseReservation", "crash-compensation");
            var invocations = new Invocations();
            var activities = new SeedActivities(invocations, gates, true);

            MaestroEngineHarness main = null;
            MaestroEngineHarness wp1 = null;
            try {
                main = node(store, mapper, Seeds.SERVICE, activities);
                main.startTimerPoller(Duration.ofMillis(200), 50);
                wp1 = node(store, mapper, Seeds.WP1_SERVICE, activities);

                drive(main, wp1, store, ds, gates);
                assertCatalogue(store, ds);
                System.out.println("Seeded-history capture invocations: " + invocations.snapshot());

                writeDump(postgres, out.resolve("seeded-history.sql"));
                writeManifest(ds, ref, out.resolve("capture.properties"));
            } finally {
                gates.releaseAll();
                if (wp1 != null) {
                    wp1.close();
                }
                if (main != null) {
                    main.close();
                }
            }
        }
    }

    // ── nodes ─────────────────────────────────────────────────────────────

    private static MaestroEngineHarness node(PostgresWorkflowStore store, JsonMapper mapper, String service,
                                             SeedActivities activities) {
        var node = MaestroEngineHarness.builder(store, mapper)
                .serviceName(service)
                .wakeRecheckInterval(Duration.ofMillis(200))
                .build();
        node.registerWorkflow(SeedOrderWorkflow.class);
        node.registerWorkflow(SeedSagaWorkflow.class);
        node.registerWorkflow(SeedFailingWorkflow.class);
        node.registerWorkflow(SeedTypedResultWorkflow.class);
        node.registerActivities(SeedOrderActivities.class, activities);
        node.registerActivities(SeedRiskActivities.class, activities);
        node.registerActivities(SeedSagaActivities.class, activities);
        node.registerActivities(SeedFailingActivities.class, activities);
        node.registerActivities(SeedTypedActivities.class, activities);
        return node;
    }

    // ── driving the catalogue, in the order the brief fixes ───────────────

    private static void drive(MaestroEngineHarness main, MaestroEngineHarness wp1, PostgresWorkflowStore store,
                              PGSimpleDataSource ds, Gates gates) throws Exception {
        // seed-completed
        startOrder(main, Seeds.COMPLETED_RUN, 1_000, 1_000, Seeds.ONE_DAY_MILLIS);
        cancelLongNap(main, store, ds, Seeds.COMPLETED_RUN);
        main.deliverSignal(Seeds.COMPLETED_RUN, "approval", CAPTURE_APPROVAL);
        TerminalWait.awaitStatus(store, Seeds.COMPLETED_RUN, WorkflowStatus.COMPLETED, WAIT);

        // seed-parked-approval
        startOrder(main, Seeds.PARKED_APPROVAL, 1_000, 1_000, Seeds.ONE_DAY_MILLIS);
        cancelLongNap(main, store, ds, Seeds.PARKED_APPROVAL);
        awaitParkedOnSignal(store, Seeds.PARKED_APPROVAL, "approval");

        // seed-terminated
        startOrder(main, Seeds.TERMINATED_RUN, 1_000, 1_000, Seeds.ONE_DAY_MILLIS);
        cancelLongNap(main, store, ds, Seeds.TERMINATED_RUN);
        awaitParkedOnSignal(store, Seeds.TERMINATED_RUN, "approval");
        assertEquals(TerminateOutcome.TERMINATED,
                main.executor().terminateWorkflow(Seeds.TERMINATED_RUN, "seeded termination"));
        TerminalWait.awaitStatus(store, Seeds.TERMINATED_RUN, WorkflowStatus.TERMINATED, WAIT);

        // seed-crashed
        startOrder(main, Seeds.CRASHED, 1_000, 1_000, Seeds.ONE_DAY_MILLIS);
        cancelLongNap(main, store, ds, Seeds.CRASHED);
        main.deliverSignal(Seeds.CRASHED, "approval", CAPTURE_APPROVAL);
        gates.awaitEntered("seed-order.charge", Seeds.CRASHED);
        TerminalWait.awaitStatus(store, Seeds.CRASHED, WorkflowStatus.RUNNING, WAIT);

        // the three sagas
        main.start(Seeds.SAGA_COMPENSATED, SeedSagaWorkflow.class, "plain");
        TerminalWait.awaitStatus(store, Seeds.SAGA_COMPENSATED, WorkflowStatus.FAILED, WAIT);
        main.start(Seeds.SAGA_COMPENSATION_FAILED, SeedSagaWorkflow.class, "fail-refund:x");
        TerminalWait.awaitStatus(store, Seeds.SAGA_COMPENSATION_FAILED, WorkflowStatus.FAILED, WAIT);
        main.start(Seeds.COMPENSATING_CRASHED, SeedSagaWorkflow.class, "crash-compensation");
        gates.awaitEntered("seed-saga.releaseReservation", "crash-compensation");
        TerminalWait.awaitStatus(store, Seeds.COMPENSATING_CRASHED, WorkflowStatus.COMPENSATING, WAIT);

        // the two failing runs
        main.start(Seeds.ACTIVITY_FAILED, SeedFailingWorkflow.class,
                new FailingRequest(Seeds.ACTIVITY_FAILED, false));
        TerminalWait.awaitStatus(store, Seeds.ACTIVITY_FAILED, WorkflowStatus.FAILED, WAIT);
        main.start(Seeds.ALERT_FAILED, SeedFailingWorkflow.class, new FailingRequest(Seeds.ALERT_FAILED, true));
        TerminalWait.awaitStatus(store, Seeds.ALERT_FAILED, WorkflowStatus.FAILED, WAIT);

        // the orphan signals
        main.deliverSignal(Seeds.ORPHAN_TARGET, "approval", CAPTURE_APPROVAL);
        deliverNote(main, Seeds.ORPHAN_TARGET);

        // the three WP1 runs
        wp1.start(Seeds.WP1_DECIMAL, SeedTypedResultWorkflow.class, new TypedRequest("DECIMAL"));
        wp1.start(Seeds.WP1_OFFSET, SeedTypedResultWorkflow.class, new TypedRequest("OFFSET"));
        wp1.start(Seeds.WP1_SET, SeedTypedResultWorkflow.class, new TypedRequest("SET"));
        for (var id : List.of(Seeds.WP1_DECIMAL, Seeds.WP1_OFFSET, Seeds.WP1_SET)) {
            awaitParkedOnSignal(store, id, "go");
        }

        // last: the two runs parked on a 10-minute wait, so neither wait can
        // fall due before the dump
        startOrder(main, Seeds.PARKED_TIMER, Seeds.TEN_MINUTES_MILLIS, 1_000, 1);
        TerminalWait.awaitStatus(store, Seeds.PARKED_TIMER, WorkflowStatus.WAITING_TIMER, WAIT);
        startOrder(main, Seeds.PARKED_EXPRESS, 1_000, Seeds.TEN_MINUTES_MILLIS, 1);
        awaitParkedOnSignal(store, Seeds.PARKED_EXPRESS, "express");
    }

    private static void startOrder(MaestroEngineHarness node, String id, long nap, long express, long cancellable) {
        node.start(id, SeedOrderWorkflow.class, new OrderRequest(id, Seeds.SKUS, nap, express, cancellable));
        deliverNote(node, id);
    }

    private static void deliverNote(MaestroEngineHarness node, String id) {
        assertTrue(node.executor().deliverSignal(id, "note", "note for " + id, "client:" + id + ":note"),
                "note for " + id + " was persisted");
    }

    /**
     * Cancels step 9's one-day sleep: the only {@code sleep-*} timer of the
     * run due more than an hour from now. Returns once the run has memoized
     * {@code TIMER_CANCELLED}.
     */
    private static void cancelLongNap(MaestroEngineHarness node, PostgresWorkflowStore store,
                                      PGSimpleDataSource ds, String id) {
        var timer = await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> longPendingSleep(ds, id), Optional::isPresent)
                .orElseThrow();
        assertTrue(node.executor().cancelTimer(id, timer.timerId(), timer.id()),
                "cancelled " + timer.timerId() + " of " + id);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> eventTypes(store, id).contains(EventType.TIMER_CANCELLED));
    }

    private record PendingTimer(UUID id, String timerId) {
    }

    private static Optional<PendingTimer> longPendingSleep(PGSimpleDataSource ds, String id) throws SQLException {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT id, timer_id FROM maestro_workflow_timer
                     WHERE workflow_id = ? AND status = 'PENDING' AND timer_id LIKE 'sleep-%'
                       AND fire_at > now() + interval '1 hour'""")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                var timer = new PendingTimer(rs.getObject(1, UUID.class), rs.getString(2));
                assertTrue(!rs.next(), "exactly one long sleep timer for " + id);
                return Optional.of(timer);
            }
        }
    }

    /** WAITING_SIGNAL, and the run's last memo is the TIMER_SCHEDULED of an await on {@code signal}. */
    private static void awaitParkedOnSignal(PostgresWorkflowStore store, String id, String signal) {
        TerminalWait.awaitStatus(store, id, WorkflowStatus.WAITING_SIGNAL, WAIT);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            var events = events(store, id);
            var last = events.getLast();
            return last.eventType() == EventType.TIMER_SCHEDULED
                    && ("$maestro:awaitSignal:" + signal).equals(last.stepName())
                    && store.getInstance(id).orElseThrow().status() == WorkflowStatus.WAITING_SIGNAL;
        });
    }

    private static List<WorkflowEvent> events(PostgresWorkflowStore store, String id) {
        return store.getEvents(store.getInstance(id).orElseThrow().id());
    }

    private static Set<EventType> eventTypes(PostgresWorkflowStore store, String id) {
        return events(store, id).stream().map(WorkflowEvent::eventType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EventType.class)));
    }

    // ── assertions before the dump ────────────────────────────────────────

    private static void assertCatalogue(PostgresWorkflowStore store, PGSimpleDataSource ds) throws SQLException {
        var mismatches = new ArrayList<String>();
        Seeds.STATUS_AT_CAPTURE.forEach((id, expected) -> {
            var actual = store.getInstance(id).orElseThrow().status();
            if (actual != expected) {
                mismatches.add(id + ": expected " + expected + " but was " + actual);
            }
        });
        assertEquals(List.of(), mismatches, "status at capture");

        var types = EnumSet.noneOf(EventType.class);
        Seeds.STATUS_AT_CAPTURE.keySet().forEach(id -> types.addAll(eventTypes(store, id)));
        assertEquals(Seeds.EVENT_TYPES, types, "event types over every seeded instance");

        assertEquals(Set.of("PENDING", "FIRED", "CANCELLED"),
                distinct(ds, "SELECT DISTINCT status FROM maestro_workflow_timer"), "timer statuses");
        assertEquals(Set.of(Seeds.SERVICE, Seeds.WP1_SERVICE),
                distinct(ds, "SELECT DISTINCT service_name FROM maestro_workflow_instance"), "services");
        assertEquals(Set.of("0"), distinct(ds,
                "SELECT (SELECT count(*) FROM maestro_distributed_lock)"
                        + " + (SELECT count(*) FROM maestro_leader_election)"), "lock rows");
        assertEquals(Set.of("true"), distinct(ds,
                "SELECT bool_and(workflow_instance_id IS NULL) FROM maestro_workflow_signal WHERE workflow_id = '"
                        + Seeds.ORPHAN_TARGET + "'"), "orphan signals have no instance");
    }

    private static Set<String> distinct(PGSimpleDataSource ds, String sql) throws SQLException {
        var values = new TreeSet<String>();
        try (var conn = ds.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(String.valueOf(rs.getObject(1)));
            }
        }
        return values;
    }

    // ── dump and manifest ─────────────────────────────────────────────────

    private static void writeDump(PostgreSQLContainer<?> postgres, Path target) throws Exception {
        var result = postgres.execInContainer(StandardCharsets.UTF_8,
                "pg_dump", "-U", "test", "-d", "maestro_seed",
                "--no-owner", "--no-privileges", "--column-inserts", "--table=public.maestro_*");
        assertEquals(0, result.getExitCode(), "pg_dump failed: " + result.getStderr());
        // pg_dump since 16.10 brackets its output in the restrict/unrestrict
        // meta-commands with a random key; older psql builds reject them, and
        // the key would make every capture differ.
        var lines = result.getStdout().lines()
                .filter(line -> !line.startsWith("\\restrict") && !line.startsWith("\\unrestrict"))
                .toList();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("INSERT INTO public.maestro_workflow_event")),
                "the dump holds workflow events");
        Files.writeString(target, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeManifest(PGSimpleDataSource ds, String ref, Path target) throws Exception {
        String serverVersion;
        try (var conn = ds.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SHOW server_version")) {
            rs.next();
            serverVersion = rs.getString(1);
        }
        var source = Files.readAllBytes(Path.of(FIXTURE_SOURCE));
        var sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        var manifest = String.join("\n",
                "# Identity of seeded-history.sql: written by SeededHistoryCapture, run by",
                "# scripts/capture-upgrade-fixture.sh history in a detached worktree of ref.",
                "# Frozen with the dump; never edit (RELEASING.md, section Upgrade fixtures).",
                "ref=" + ref,
                "capturedAt=" + Instant.now(),
                "serverVersion=" + serverVersion,
                "fixtureSource.path=" + FIXTURE_SOURCE,
                "fixtureSource.sha256=" + sha256,
                "");
        Files.writeString(target, manifest, StandardCharsets.ISO_8859_1);
    }

    // ── gates ─────────────────────────────────────────────────────────────

    /**
     * Holds chosen {@code (step, key)} calls until released, so a run can be
     * left mid-activity when the history is dumped. Every other call passes.
     */
    private static final class Gates implements StepGate {

        private record Hold(CountDownLatch entered, CountDownLatch release) {
        }

        private final Map<String, Hold> holds = new ConcurrentHashMap<>();

        void hold(String step, String key) {
            holds.put(step + "|" + key, new Hold(new CountDownLatch(1), new CountDownLatch(1)));
        }

        void awaitEntered(String step, String key) throws InterruptedException {
            assertTrue(holds.get(step + "|" + key).entered().await(WAIT.toSeconds(), TimeUnit.SECONDS),
                    step + " for " + key + " was entered");
        }

        void releaseAll() {
            holds.values().forEach(h -> h.release().countDown());
        }

        @Override
        public void enter(String step, String key) {
            var hold = holds.get(step + "|" + key);
            if (hold == null) {
                return;
            }
            hold.entered().countDown();
            try {
                hold.release().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("gate " + step + " for " + key + " interrupted", e);
            }
        }
    }
}
