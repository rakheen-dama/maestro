package io.b2mash.maestro.integration.upgrade.rolling;

import io.b2mash.maestro.integration.upgrade.rolling.RollingDatabase.SideEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The two-build rolling test (Issue 41): a node of a previous release and a
 * node of this build run at the same time against one database, with one
 * {@code maestro.service-name}, and runs parked by either are adopted, woken
 * and finished by the other.
 *
 * <h2>The nodes</h2>
 * <p>Each node is a JVM of its own, launched from one build's classpath
 * ({@link RollingNodeManifest}): the {@code rolling-node} project's one source
 * set, compiled against this build and against the previous release's
 * published artifacts. Both builds therefore run the same workflow source. A
 * node is a plain Spring Boot application on the starter, the Postgres store
 * and lock, with engine defaults except a 1 s recovery poll, a 500 ms timer
 * poll and a 10 s instance-lock TTL (so a killed node's runs are adopted within
 * seconds). The wake recheck stays at its 30 s default, so a wake asserted
 * within {@link #WAKE_TIMEOUT} came through {@code LISTEN/NOTIFY}. There is no
 * Kafka: signals enter through {@code MaestroClient}, with or without a
 * client-supplied message id.
 *
 * <h2>The previous release is a parameter</h2>
 * <p>The class runs once per previous release the manifest lists (the rows in
 * {@code rolling-node/build.gradle.kts}). A later release adds a row there and
 * nothing here.
 *
 * <h2>What each direction must do</h2>
 * <ul>
 *   <li><b>Upgrade</b> (parked by the previous release, adopted by this build):
 *       the run must finish exactly as it would have on one build. A stand-down
 *       here fails the test: this build must resume anything the previous
 *       release parked.</li>
 *   <li><b>Rollback</b> (parked by this build, adopted by the previous
 *       release). The previous release may either
 *       <ol type="a">
 *         <li>finish the run correctly, or</li>
 *         <li>stand down, having recorded only what a correct run records (its
 *             history is a prefix of the history the run finally has), never a
 *             {@code FAILED} or {@code TERMINATED} status, never a
 *             compensation in a run that does not fail, and never losing a
 *             signal. A node of this build must then take the run back and
 *             finish it correctly; the steps after the park may have run on
 *             either node, each exactly once.</li>
 *       </ol>
 *       Which one happened is written to {@code results.txt} (CI turns a
 *       stand-down into a warning); (b) is not a failure of this test, since
 *       {@code docs/operations.md} §10.3 allows it.</li>
 * </ul>
 *
 * <p>Every outcome is asserted on the persisted rows, read by raw SQL: the
 * status, the events (no failure or compensation event the workflow did not
 * cause), the signal rows (each delivered signal stored once and consumed),
 * and the side-effect table (each live step ran exactly once, on a node
 * allowed for it, except an activity in flight when its node was killed,
 * which may run again: the documented at-least-once window). Every node's
 * observer output is checked too: no {@code standDown} outside (b),
 * no {@code activityResultUnreadable} or unexpected {@code workflowFailed},
 * and {@code runAbandoned} only for {@code SHUTDOWN}.
 *
 * <p>{@code results.txt} holds one line per scenario run: passed (with what it
 * observed), failed (with the first line of the failure) or aborted.
 *
 * <p>Run it with {@code ./gradlew :maestro-integration-tests:rollingUpgradeTest}
 * (see the task's comment for where the previous release comes from). It is
 * not part of {@code ./gradlew build}.
 */
@Tag("rolling-upgrade")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
@ParameterizedClass(name = "previous release {0}")
@MethodSource("previousReleases")
class RollingUpgradeIT {

    private static final RollingNodeManifest MANIFEST = RollingNodeManifest.load();

    private static final String SIGNAL_TYPE = "rolling-signal";
    private static final String TIMER_TYPE = "rolling-timer";
    private static final String SAGA_TYPE = "rolling-saga";

    /** Starting a run and reaching its first park. */
    private static final Duration PARK_TIMEOUT = Duration.ofSeconds(30);
    /** A node's first recovery pass, or a killed node's lock expiring (10 s TTL) and then that pass. */
    private static final Duration ADOPT_TIMEOUT = Duration.ofSeconds(60);
    /** A signal or timer wake and the rest of the run; includes a timer-leader handover (15 s TTL). */
    private static final Duration FINISH_TIMEOUT = Duration.ofSeconds(60);
    /** A cross-node signal wake: below the 30 s wake recheck, so only NOTIFY can meet it. */
    private static final Duration WAKE_TIMEOUT = Duration.ofSeconds(15);

    private static final Set<String> ACTIVE = Set.of("RUNNING", "WAITING_SIGNAL", "WAITING_TIMER", "COMPENSATING");

    static Stream<String> previousReleases() {
        return MANIFEST.previousVersions().stream();
    }

    /** One build of the node. */
    private record NodeBuild(String label, String classpath, boolean previous) {}

    /** How a run adopted by the other build ended. */
    private enum Outcome { FINISHED, STOOD_DOWN }

    private enum Direction { UPGRADE, ROLLBACK, MIXED }

    /** One expected side-effect row: the activity, the phase, and the nodes allowed to have run it. */
    private record Expected(String activity, String phase, Set<String> nodes) {
        static Expected on(String activity, String phase, List<RollingNode> allowed) {
            return new Expected(activity, phase, allowed.stream().map(RollingNode::name)
                    .collect(Collectors.toUnmodifiableSet()));
        }

        boolean matches(SideEffect row) {
            return row.activity().equals(activity) && row.phase().equals(phase) && nodes.contains(row.node());
        }
    }

    /**
     * Where a run adopted by the other build was finished: by the adopter
     * alone ({@code later} = the adopter), or, after a rollback stand-down, by
     * a node of this build ({@code later} = the adopter and that node).
     */
    private record Finish(RollingNode finisher, List<RollingNode> later) {}

    private final String previousVersion;
    private final List<RollingNode> nodes = new ArrayList<>();
    private RollingDatabase db;
    private Path logDir;
    private String scenario;
    private Direction direction;
    private String observation;

    /** Writes one {@code results.txt} line per scenario run, whatever its outcome. */
    @RegisterExtension
    final TestWatcher resultsWriter = new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
            writeResult("PASSED", observation == null ? "(no observation recorded)" : observation);
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            writeResult("FAILED", firstLine(cause));
        }

        @Override
        public void testAborted(ExtensionContext context, Throwable cause) {
            writeResult("ABORTED", firstLine(cause));
        }

        @Override
        public void testDisabled(ExtensionContext context, Optional<String> reason) {
            writeResult("DISABLED", reason.orElse(""));
        }
    };

    RollingUpgradeIT(String previousVersion) {
        this.previousVersion = previousVersion;
    }

    @BeforeEach
    void setUp(TestInfo info) {
        scenario = info.getTestMethod().orElseThrow().getName();
        logDir = logRoot().resolve(previousVersion).resolve(scenario);
        db = RollingDatabase.create("rolling_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        System.out.println("[rolling] " + scenario + ": previous release " + previousVersion + " from "
                + MANIFEST.previousSource());
    }

    @AfterEach
    void stopNodes() {
        nodes.forEach(RollingNode::close);
    }

    // ── 1. Parked on a signal: upgrade, and rollback (scenario 3) ──────────

    @Test
    void signalParkedOnPreviousIsResumedByCurrent() {
        signalParkedThenAdopted(previous(), current(), Direction.UPGRADE);
    }

    @Test
    void signalParkedOnCurrentIsAdoptedByPrevious() {
        signalParkedThenAdopted(current(), previous(), Direction.ROLLBACK);
    }

    private void signalParkedThenAdopted(NodeBuild parkedOn, NodeBuild adoptedBy, Direction direction) {
        this.direction = direction;
        var wf = workflowId("signal");
        var first = launch(parkedOn, "a");
        first.start(SIGNAL_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.stopGracefully();

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);
        second.signal(wf, "go", "hello");

        var finish = finish(wf, "COMPLETED", direction, second, false);
        assertCompletedCleanly(wf, "\"signalled:hello\"", List.of(
                Expected.on("before", "done", List.of(first)),
                Expected.on("after", "done", finish.later())));
        assertSignalsStoredOnceAndConsumed(wf, "go");
        assertNoAlarms(wf, false, first);
        assertNoAlarms(wf, false, finish.finisher());
        observed("signal parked on " + parkedOn.label() + ", adopted and signalled on " + adoptedBy.label()
                + ": " + finishedBy(finish, "COMPLETED"));
    }

    // ── 2. Parked on a timer: upgrade, and rollback (scenario 3) ───────────

    @Test
    void timerParkedOnPreviousIsFiredByCurrent() {
        timerParkedThenAdopted(previous(), current(), Direction.UPGRADE);
    }

    @Test
    void timerParkedOnCurrentIsAdoptedByPrevious() {
        timerParkedThenAdopted(current(), previous(), Direction.ROLLBACK);
    }

    private void timerParkedThenAdopted(NodeBuild parkedOn, NodeBuild adoptedBy, Direction direction) {
        this.direction = direction;
        var wf = workflowId("timer");
        var first = launch(parkedOn, "a");
        first.start(TIMER_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_TIMER", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.stopGracefully();

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);
        assertThat(db.makePendingTimersDue(wf)).as("the parked run's pending sleep timer").isEqualTo(1);

        var finish = finish(wf, "COMPLETED", direction, second, false);
        assertCompletedCleanly(wf, "\"slept\"", List.of(
                Expected.on("before", "done", List.of(first)),
                Expected.on("after", "done", finish.later())));
        assertSignalsStoredOnceAndConsumed(wf);
        assertNoAlarms(wf, false, first);
        assertNoAlarms(wf, false, finish.finisher());
        observed("timer parked on " + parkedOn.label() + ", adopted and fired on " + adoptedBy.label()
                + ": " + finishedBy(finish, "COMPLETED"));
    }

    // ── 4. Both alive: parked on one build, signalled through the other ────

    @Test
    void parkedOnPreviousIsWokenThroughCurrent() {
        wokenThroughTheOtherNode(previous(), current());
    }

    @Test
    void parkedOnCurrentIsWokenThroughPrevious() {
        wokenThroughTheOtherNode(current(), previous());
    }

    private void wokenThroughTheOtherNode(NodeBuild ownerBuild, NodeBuild otherBuild) {
        direction = Direction.MIXED;
        var wf = workflowId("wake");
        var owner = launch(ownerBuild, "owner");
        var other = launch(otherBuild, "other");
        owner.start(SIGNAL_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, owner, other);
        var epoch = db.leaseEpoch(wf);

        var sent = System.nanoTime();
        other.signal(wf, "go", "across");
        awaitStatus(wf, "COMPLETED", WAKE_TIMEOUT, owner, other);
        var wakeMillis = Duration.ofNanos(System.nanoTime() - sent).toMillis();

        assertCompletedCleanly(wf, "\"signalled:across\"", List.of(
                Expected.on("before", "done", List.of(owner)),
                Expected.on("after", "done", List.of(owner))));
        assertSignalsStoredOnceAndConsumed(wf, "go");
        assertThat(db.leaseEpoch(wf)).as("the run never left its owner").isEqualTo(epoch);
        assertNoAlarms(wf, false, owner, other);
        observed("parked on " + ownerBuild.label() + ", signalled through " + otherBuild.label()
                + " (both alive): COMPLETED on the owner, woken in " + wakeMillis + " ms");
    }

    // ── 4b. Both alive: one signal, one client message id, through both builds ─

    @Test
    void aSignalWithOneIdThroughPreviousThenCurrentIsStoredOnce() {
        sameSignalIdThroughBothBuilds(previous(), current());
    }

    @Test
    void aSignalWithOneIdThroughCurrentThenPreviousIsStoredOnce() {
        sameSignalIdThroughBothBuilds(current(), previous());
    }

    /**
     * The same logical signal, carrying one client-supplied message id, is
     * delivered through each build in turn, as a retrying caller behind a
     * load balancer would. Both builds must derive the same stored id
     * ({@code client:<id>}), so the second delivery is suppressed: one signal
     * row, one consumption, and the run sees the first payload only.
     */
    private void sameSignalIdThroughBothBuilds(NodeBuild firstSender, NodeBuild secondSender) {
        direction = Direction.MIXED;
        var wf = workflowId("dedup");
        var a = launch(firstSender, "a");
        var b = launch(secondSender, "b");
        a.start(SIGNAL_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, a, b);
        var messageId = wf + ":go";

        assertThat(a.signal(wf, "go", "first", messageId))
                .as("the first delivery, through " + a.name() + "\n" + diagnostics(wf)).isTrue();
        assertThat(b.signal(wf, "go", "second", messageId))
                .as("the duplicate through " + b.name() + " is suppressed\n" + diagnostics(wf)).isFalse();
        awaitStatus(wf, "COMPLETED", FINISH_TIMEOUT, a, b);

        assertCompletedCleanly(wf, "\"signalled:first\"", List.of(
                Expected.on("before", "done", List.of(a)),
                Expected.on("after", "done", List.of(a))));
        var signals = db.signals(wf);
        assertThat(signals).as("one stored signal, under the client namespace\n" + diagnostics(wf))
                .containsExactly(new RollingDatabase.Signal("go", true, "client:" + messageId));
        assertThat(eventTypes(wf)).as("one consumption").containsOnlyOnce("SIGNAL_RECEIVED");
        assertNoAlarms(wf, false, a, b);
        observed("one signal id through " + firstSender.label() + " then " + secondSender.label()
                + ": stored once as client:<id>, the second suppressed, consumed once");
    }

    // ── 5. Saga: compensations registered under one build, run by the other ─

    @Test
    void sagaParkedOnPreviousIsCompensatedByCurrent() {
        sagaParkedThenCompensated(previous(), current(), Direction.UPGRADE);
    }

    @Test
    void sagaParkedOnCurrentIsCompensatedByPrevious() {
        sagaParkedThenCompensated(current(), previous(), Direction.ROLLBACK);
    }

    private void sagaParkedThenCompensated(NodeBuild parkedOn, NodeBuild adoptedBy, Direction direction) {
        this.direction = direction;
        var wf = workflowId("saga");
        var first = launch(parkedOn, "a");
        first.start(SAGA_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.stopGracefully();

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);
        second.signal(wf, "decide", "ship");

        var finish = finish(wf, "FAILED", direction, second, true);
        var later = finish.later();
        assertCompensatedCleanly(wf, finish.finisher(), List.of(
                Expected.on("reserve", "done", List.of(first)),
                Expected.on("charge", "done", List.of(first)),
                Expected.on("ship", "done", later),
                Expected.on("refund", "started", later),
                Expected.on("refund", "done", later),
                Expected.on("release", "done", later)));
        assertSignalsStoredOnceAndConsumed(wf, "decide");
        assertNoAlarms(wf, false, first);
        assertNoAlarms(wf, true, finish.finisher());
        observed("saga parked on " + parkedOn.label() + ", failed and compensated on " + adoptedBy.label() + ": "
                + finishedBy(finish, "FAILED by its own failure, both compensations once"));
    }

    @Test
    void compensationInFlightOnPreviousIsFinishedByCurrent() {
        compensationInFlightThenAdopted(previous(), current(), Direction.UPGRADE);
    }

    @Test
    void compensationInFlightOnCurrentIsFinishedByPrevious() {
        compensationInFlightThenAdopted(current(), previous(), Direction.ROLLBACK);
    }

    /**
     * The saga fails and starts compensating on one build; its first
     * compensation ({@code refund}) is held in flight by its gate and the node
     * is killed (SIGKILL: a crash, so the dying node can write nothing). The
     * other build adopts the {@code COMPENSATING} run once the lock expires,
     * runs {@code refund} again (the activity in flight at adoption: the
     * at-least-once window) and {@code release}, and ends the run
     * {@code FAILED}.
     */
    private void compensationInFlightThenAdopted(NodeBuild crashedOn, NodeBuild adoptedBy, Direction direction) {
        this.direction = direction;
        var wf = workflowId("gated-saga");
        var first = launch(crashedOn, "a");
        first.start(SAGA_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, first);
        first.signal(wf, "decide", "ship");
        RollingDatabase.await("refund to start on " + first.name(), PARK_TIMEOUT,
                () -> db.sideEffects(wf).contains(new SideEffect("refund", "started", first.name())),
                () -> diagnostics(wf, first));
        awaitStatus(wf, "COMPENSATING", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.kill();
        db.openGate(wf);

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);

        var finish = finish(wf, "FAILED", direction, second, true);
        var later = finish.later();
        assertCompensatedCleanly(wf, finish.finisher(), List.of(
                Expected.on("reserve", "done", List.of(first)),
                Expected.on("charge", "done", List.of(first)),
                Expected.on("ship", "done", List.of(first)),
                Expected.on("refund", "started", List.of(first)),
                Expected.on("refund", "started", later),
                Expected.on("refund", "done", later),
                Expected.on("release", "done", later)));
        assertSignalsStoredOnceAndConsumed(wf, "decide");
        assertNoAlarms(wf, true, finish.finisher());
        observed("compensation in flight on " + crashedOn.label() + " (killed), adopted by " + adoptedBy.label()
                + ": " + finishedBy(finish, "FAILED by its own failure; refund started twice (at-least-once "
                + "window), each compensation completed once"));
    }

    // ── Outcome handling ───────────────────────────────────────────────────

    /**
     * Waits for the adopted run to reach {@code expected}; in the rollback
     * direction a stand-down on the adopter is the other allowed outcome, after
     * which this rolls forward to a node of this build (rule (b) of the class
     * Javadoc). Any other terminal status fails at once, and so does a
     * stand-down in any other direction.
     *
     * @param compensates whether the correct run fails and compensates (a
     *                    stand-down may then leave it {@code COMPENSATING})
     * @return who finished the run
     */
    private Finish finish(String wf, String expected, Direction direction, RollingNode adopter,
                          boolean compensates) {
        var outcome = awaitOutcome(wf, expected, direction, adopter);
        if (outcome == Outcome.FINISHED) {
            return new Finish(adopter, List.of(adopter));
        }
        return rollForward(wf, expected, adopter, compensates);
    }

    /**
     * Waits until the adopted run reaches {@code expected}, or — in the
     * rollback direction only — the adopter reports a stand-down for it. A
     * run that ends in any other terminal status fails at once.
     */
    private Outcome awaitOutcome(String wf, String expected, Direction direction, RollingNode adopter) {
        var standDownAllowed = direction == Direction.ROLLBACK;
        RollingDatabase.await(expected + (standDownAllowed ? " or a stand-down on " + adopter.name() : ""),
                FINISH_TIMEOUT,
                () -> {
                    var status = db.status(wf).orElse("");
                    if (!status.isEmpty() && !ACTIVE.contains(status) && !status.equals(expected)) {
                        fail("Workflow " + wf + " ended " + status + ", expected " + expected + "\n"
                                + diagnostics(wf, adopter));
                    }
                    if (!standDownAllowed && hasCallback(adopter, wf, "standDown")) {
                        fail("This build stood down a run the previous release parked: " + adopter.events(wf)
                                + "\n" + diagnostics(wf, adopter));
                    }
                    return status.equals(expected) || (standDownAllowed && hasCallback(adopter, wf, "standDown"));
                },
                () -> diagnostics(wf, adopter));
        return db.status(wf).orElse("").equals(expected) ? Outcome.FINISHED : Outcome.STOOD_DOWN;
    }

    /**
     * Rule (b): the previous release stood the run down. Stops that node,
     * checks what it left (no failed or terminated status, no compensation in a
     * run that does not fail, no failure callback), launches a node of this
     * build and requires it to finish the run with {@code expected}, then
     * checks that the history the stood-down node left is a prefix of the
     * final history. The caller's assertions then pin the rest of the correct
     * run (steps after the park on either node, each exactly once; every
     * signal stored once and consumed).
     */
    private Finish rollForward(String wf, String expected, RollingNode stoodDown, boolean compensates) {
        var standDown = stoodDown.events(wf).stream().filter(e -> e.callback().equals("standDown"))
                .findFirst().orElseThrow();
        var epoch = db.leaseEpoch(wf);
        stoodDown.stopGracefully();

        var left = db.events(wf);
        var status = db.status(wf).orElseThrow();
        var allowed = compensates ? ACTIVE : Set.of("RUNNING", "WAITING_SIGNAL", "WAITING_TIMER");
        assertThat(status).as("status the stand-down left\n" + diagnostics(wf)).isIn(allowed);
        assertThat(left).as("history the stand-down left\n" + diagnostics(wf))
                .noneMatch(e -> e.type().equals("WORKFLOW_FAILED") || e.type().equals("COMPENSATION_STEP_FAILED")
                        || (!compensates && e.type().startsWith("COMPENSATION_")));
        assertThat(stoodDown.events(wf)).as("callbacks on the node that stood down")
                .noneMatch(e -> e.callback().equals("workflowFailed")
                        || e.callback().equals("activityResultUnreadable"));

        var third = launch(current(), "c");
        awaitAdopted(wf, epoch, third);
        // A durable sleep's hour passes again for the rolled-forward node (an
        // await's deadline timer is left alone: its signal is already stored).
        if (db.events(wf).stream().anyMatch(e -> e.type().equals("TIMER_SCHEDULED")
                && "$maestro:sleep".equals(e.step()))) {
            db.makePendingTimersDue(wf);
        }
        RollingDatabase.await("the rolled-forward run to reach " + expected, FINISH_TIMEOUT,
                () -> !ACTIVE.contains(db.status(wf).orElse("RUNNING")), () -> diagnostics(wf, third));
        assertThat(db.status(wf)).as("the rolled-forward run\n" + diagnostics(wf, third)).contains(expected);
        assertThat(db.events(wf)).as("the stood-down node's history is a prefix of the final one\n"
                + diagnostics(wf)).startsWith(left.toArray(RollingDatabase.Event[]::new));
        assertNoAlarms(wf, compensates, third);

        observation = "STOOD DOWN " + standDown.reason() + " (" + standDown.detail() + ") on " + stoodDown.name()
                + " after " + left.size() + " events, status " + status + "; rolled forward";
        return new Finish(third, List.of(stoodDown, third));
    }

    private String finishedBy(Finish finish, String outcome) {
        if (finish.later().size() == 1) {
            return outcome;
        }
        return observation + " to " + finish.finisher().name() + ": " + outcome;
    }

    // ── Assertions on the persisted outcome ────────────────────────────────

    /**
     * {@code COMPLETED} with {@code output}, no failure or compensation event,
     * and exactly the expected side-effect rows, in order, each on a node
     * allowed for it.
     */
    private void assertCompletedCleanly(String wf, String output, List<Expected> sideEffects) {
        var diagnostics = diagnostics(wf);
        assertThat(db.status(wf)).as(diagnostics).contains("COMPLETED");
        assertThat(db.output(wf)).as(diagnostics).contains(output);
        assertThat(eventTypes(wf)).as(diagnostics).doesNotContain(
                "ACTIVITY_FAILED", "WORKFLOW_FAILED", "COMPENSATION_STARTED", "COMPENSATION_STEP_FAILED", "UNKNOWN");
        assertSideEffects(wf, sideEffects, diagnostics);
    }

    /**
     * {@code FAILED} by the saga's own failure ({@code ship}), no failed
     * compensation, exactly the expected side-effect rows, and the failure
     * reported by the node that recorded it.
     */
    private void assertCompensatedCleanly(String wf, RollingNode finisher, List<Expected> sideEffects) {
        var diagnostics = diagnostics(wf);
        assertThat(db.status(wf)).as(diagnostics).contains("FAILED");
        var failed = db.events(wf).stream().filter(e -> e.type().equals("WORKFLOW_FAILED")).toList();
        assertThat(failed).as(diagnostics).hasSize(1);
        assertThat(failed.getFirst().payload()).as("the run failed by its own failure\n" + diagnostics)
                .contains("ship refused for " + wf);
        assertThat(eventTypes(wf)).as(diagnostics).doesNotContain("COMPENSATION_STEP_FAILED", "UNKNOWN");
        assertSideEffects(wf, sideEffects, diagnostics);
        assertThat(finisher.events(wf)).as("the node that recorded the failure reported it")
                .anyMatch(e -> e.callback().equals("workflowFailed"));
    }

    private void assertSideEffects(String wf, List<Expected> expected, String diagnostics) {
        var rows = db.sideEffects(wf);
        var message = "side effects " + rows + " against " + expected + "\n" + diagnostics;
        assertThat(rows).as(message).hasSameSizeAs(expected);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(expected.get(i).matches(rows.get(i))).as("row " + i + ": " + message).isTrue();
        }
    }

    /** Each signal name delivered to the run was stored exactly once and consumed: none lost, none doubled. */
    private void assertSignalsStoredOnceAndConsumed(String wf, String... signalNames) {
        var signals = db.signals(wf);
        assertThat(signals).as("signal rows\n" + diagnostics(wf)).hasSize(signalNames.length);
        assertThat(signals.stream().map(RollingDatabase.Signal::name).toList())
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(signalNames));
        assertThat(signals).as("every signal consumed\n" + diagnostics(wf)).allMatch(RollingDatabase.Signal::consumed);
    }

    /**
     * No node reported a stand-down or an unreadable result, and a run ended
     * without an outcome only because its node shut down. A workflow failure is
     * an alarm too, except where the scenario's own failure is expected
     * ({@code failureExpected}; {@link #assertCompensatedCleanly} pins which
     * failure).
     */
    private void assertNoAlarms(String wf, boolean failureExpected, RollingNode... nodesToCheck) {
        for (var node : nodesToCheck) {
            assertThat(node.events(wf)).as("callbacks on " + node.name() + "\n" + diagnostics(wf))
                    .noneMatch(e -> e.callback().equals("standDown"))
                    .noneMatch(e -> e.callback().equals("activityResultUnreadable"))
                    .noneMatch(e -> e.callback().equals("runAbandoned") && !e.reason().equals("SHUTDOWN"))
                    .noneMatch(e -> e.callback().equals("workflowFailed") && !failureExpected);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private NodeBuild previous() {
        return new NodeBuild("previous-" + previousVersion, MANIFEST.previousClasspath(previousVersion), true);
    }

    private static NodeBuild current() {
        return new NodeBuild("current", MANIFEST.currentClasspath(), false);
    }

    private RollingNode launch(NodeBuild build, String suffix) {
        var properties = new LinkedHashMap<>(db.datasourceProperties());
        properties.put("maestro.service-name", "rolling");
        properties.put("maestro.recovery.poll-interval", "1s");
        properties.put("maestro.timer.poll-interval", "500ms");
        properties.put("maestro.lock.ttl", "10s");
        var node = RollingNode.launch(build.label() + "-" + suffix, build.classpath(), properties, logDir);
        nodes.add(node);
        assertRunsItsBuild(node, build);
        return node;
    }

    /**
     * A node of a previous release loaded the engine and the starter from that
     * release's artifacts; a node of this build loaded neither from any
     * published version, but from this build's own module outputs.
     */
    private void assertRunsItsBuild(RollingNode node, NodeBuild build) {
        if (build.previous()) {
            assertThat(node.engineLocation()).as(node.name() + "'s maestro-core")
                    .endsWith("/maestro-core-" + previousVersion + ".jar");
            assertThat(node.starterLocation()).as(node.name() + "'s maestro-spring-boot-starter")
                    .endsWith("/maestro-spring-boot-starter-" + previousVersion + ".jar");
        } else {
            assertThat(node.engineLocation()).as(node.name() + "'s maestro-core").contains("/maestro-core/build/");
            assertThat(node.starterLocation()).as(node.name() + "'s maestro-spring-boot-starter")
                    .contains("/maestro-spring-boot-starter/build/");
        }
    }

    private void awaitStatus(String wf, String status, Duration timeout, RollingNode... watched) {
        RollingDatabase.await(wf + " to reach " + status, timeout,
                () -> db.status(wf).orElse("").equals(status), () -> diagnostics(wf, watched));
    }

    /** Waits for a new adoption of the run: every adoption raises the lease epoch. */
    private void awaitAdopted(String wf, long epochBefore, RollingNode adopter) {
        RollingDatabase.await(adopter.name() + " to adopt " + wf, ADOPT_TIMEOUT,
                () -> db.leaseEpoch(wf) > epochBefore, () -> diagnostics(wf, adopter));
    }

    private static boolean hasCallback(RollingNode node, String wf, String callback) {
        return node.events(wf).stream().anyMatch(e -> e.callback().equals(callback));
    }

    private List<String> eventTypes(String wf) {
        return db.events(wf).stream().map(RollingDatabase.Event::type).toList();
    }

    private String workflowId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String diagnostics(String wf, RollingNode... watched) {
        var text = new StringBuilder();
        text.append("status=").append(db.status(wf).orElse("<none>")).append('\n');
        text.append("events:\n").append(db.events(wf).stream()
                .map(e -> "  " + e.sequence() + " " + e.type() + " " + e.step() + " " + e.payload())
                .collect(Collectors.joining("\n"))).append('\n');
        text.append("signals: ").append(db.signals(wf)).append('\n');
        text.append("side effects: ").append(db.sideEffects(wf)).append('\n');
        var all = watched.length == 0 ? nodes : List.of(watched);
        for (var node : all) {
            text.append("node ").append(node.name()).append(" callbacks: ").append(node.events(wf))
                    .append(" (log ").append(node.log()).append(")\n");
            if (watched.length > 0) {
                text.append(node.tail()).append('\n');
            }
        }
        return text.toString();
    }

    /** Records what the scenario observed; {@link #resultsWriter} writes it once the scenario passes. */
    private void observed(String line) {
        observation = line;
    }

    private static String firstLine(Throwable cause) {
        var message = String.valueOf(cause);
        var newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private void writeResult(String outcome, String detail) {
        var text = previousVersion + " | " + (direction == null ? "-" : direction) + " | " + scenario + " | "
                + outcome + " | " + detail;
        System.out.println("[rolling] " + text);
        try {
            Files.createDirectories(logRoot());
            Files.writeString(logRoot().resolve("results.txt"), text + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path logRoot() {
        return Path.of(System.getProperty("maestro.rolling.logDir", "build/rolling-upgrade"));
    }
}
