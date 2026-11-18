package io.b2mash.maestro.integration.upgrade.rolling;

import io.b2mash.maestro.integration.upgrade.rolling.RollingDatabase.SideEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Kafka: no scenario needs it (signals enter through {@code MaestroClient}).
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
 *       release): the previous release must either finish the run correctly
 *       or stand down without writing anything ({@code docs/operations.md}
 *       §10.1, §10.3). Which one happened is recorded in
 *       {@code results.txt}; after a stand-down the test rolls forward and
 *       requires this build to finish the run.</li>
 * </ul>
 *
 * <p>Every outcome is asserted on the persisted rows, read by raw SQL: the
 * status, the events (no failure or compensation event the workflow did not
 * cause), and the side-effect table (each live step ran exactly once, on the
 * node expected, except an activity in flight when its node was killed, which
 * may run again: the documented at-least-once window). Every node's observer
 * output is checked too: no {@code standDown}, {@code activityResultUnreadable}
 * or unexpected {@code workflowFailed}, and {@code runAbandoned} only for
 * {@code SHUTDOWN}.
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

    private final String previousVersion;
    private final List<RollingNode> nodes = new ArrayList<>();
    private RollingDatabase db;
    private Path logDir;
    private String scenario;

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
        var wf = workflowId("signal");
        var first = launch(parkedOn, "a");
        first.start(SIGNAL_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.stopGracefully();

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);
        second.signal(wf, "go", "hello");

        var outcome = awaitOutcome(wf, "COMPLETED", direction, second);
        if (outcome == Outcome.FINISHED) {
            assertCompletedCleanly(wf, "\"signalled:hello\"", steps("before", first, "after", second));
            assertNoAlarms(wf, false, first, second);
            result(direction, "signal parked on " + parkedOn.label() + ", adopted and signalled on "
                    + adoptedBy.label() + ": COMPLETED");
            return;
        }
        rollForward(wf, second, "signal parked on " + parkedOn.label(), third -> {
            assertCompletedCleanly(wf, "\"signalled:hello\"", steps("before", first, "after", third));
            assertNoAlarms(wf, false, first, third);
        });
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
        var wf = workflowId("timer");
        var first = launch(parkedOn, "a");
        first.start(TIMER_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_TIMER", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.stopGracefully();

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);
        assertThat(db.makePendingTimersDue(wf)).as("the parked run's pending sleep timer").isEqualTo(1);

        var outcome = awaitOutcome(wf, "COMPLETED", direction, second);
        if (outcome == Outcome.FINISHED) {
            assertCompletedCleanly(wf, "\"slept\"", steps("before", first, "after", second));
            assertNoAlarms(wf, false, first, second);
            result(direction, "timer parked on " + parkedOn.label() + ", adopted and fired on "
                    + adoptedBy.label() + ": COMPLETED");
            return;
        }
        rollForward(wf, second, "timer parked on " + parkedOn.label(), third -> {
            assertCompletedCleanly(wf, "\"slept\"", steps("before", first, "after", third));
            assertNoAlarms(wf, false, first, third);
        });
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

        assertCompletedCleanly(wf, "\"signalled:across\"", steps("before", owner, "after", owner));
        assertThat(db.leaseEpoch(wf)).as("the run never left its owner").isEqualTo(epoch);
        assertNoAlarms(wf, false, owner, other);
        result(Direction.MIXED, "parked on " + ownerBuild.label() + ", signalled through " + otherBuild.label()
                + " (both alive): COMPLETED on the owner, woken in " + wakeMillis + " ms");
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
        var wf = workflowId("saga");
        var first = launch(parkedOn, "a");
        first.start(SAGA_TYPE, wf, wf);
        awaitStatus(wf, "WAITING_SIGNAL", PARK_TIMEOUT, first);
        var epoch = db.leaseEpoch(wf);
        first.stopGracefully();

        var second = launch(adoptedBy, "b");
        awaitAdopted(wf, epoch, second);
        second.signal(wf, "decide", "ship");

        var outcome = awaitOutcome(wf, "FAILED", direction, second);
        if (outcome == Outcome.FINISHED) {
            assertCompensatedCleanly(wf, first, second, List.of(second));
            assertNoAlarms(wf, false, first);
            assertNoAlarms(wf, true, second);
            result(direction, "saga parked on " + parkedOn.label() + ", failed and compensated on "
                    + adoptedBy.label() + ": FAILED by its own failure, both compensations once");
            return;
        }
        rollForward(wf, second, "saga parked on " + parkedOn.label(), third -> {
            assertCompensatedCleanly(wf, first, third, List.of(third));
            assertNoAlarms(wf, false, first);
            assertNoAlarms(wf, true, third);
        });
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

        var outcome = awaitOutcome(wf, "FAILED", direction, second);
        if (outcome == Outcome.FINISHED) {
            assertCompensatedCleanly(wf, first, second, List.of(first, second));
            assertNoAlarms(wf, true, second);
            result(direction, "compensation in flight on " + crashedOn.label() + " (killed), adopted by "
                    + adoptedBy.label() + ": FAILED by its own failure; refund started twice (at-least-once "
                    + "window), each compensation completed once");
            return;
        }
        rollForward(wf, second, "compensation in flight on " + crashedOn.label(), third -> {
            assertCompensatedCleanly(wf, first, third, List.of(first, third));
            assertNoAlarms(wf, true, third);
        });
    }

    // ── Outcome handling ───────────────────────────────────────────────────

    private enum Direction { UPGRADE, ROLLBACK, MIXED }

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

    /** The interface a scenario's clean-finish assertion is given after a roll-forward. */
    private interface Finisher {
        void assertFinished(RollingNode finisher);
    }

    /**
     * After the previous release stood a run down: checks that it recorded no
     * failure and left the run recoverable (progress it made correctly before
     * standing down is allowed), records what it reported, then rolls forward
     * to this build and requires the run to finish cleanly.
     */
    private void rollForward(String wf, RollingNode stoodDown, String what, Finisher finisher) {
        var standDowns = stoodDown.events(wf).stream().filter(e -> e.callback().equals("standDown")).toList();
        assertThat(db.status(wf).orElseThrow()).as("status after the stand-down").isIn(ACTIVE);
        assertThat(eventTypes(wf)).as("events after the stand-down").doesNotContain(
                "WORKFLOW_FAILED", "COMPENSATION_STEP_FAILED");
        assertThat(stoodDown.events(wf)).as("callbacks on the node that stood down")
                .noneMatch(e -> e.callback().equals("workflowFailed")
                        || e.callback().equals("activityResultUnreadable"));
        result(Direction.ROLLBACK, what + ", adopted by " + stoodDown.name() + ": STOOD DOWN "
                + standDowns.getFirst().reason() + " (" + standDowns.getFirst().detail() + "); rolled forward");
        var epoch = db.leaseEpoch(wf);
        stoodDown.stopGracefully();
        var third = launch(current(), "c");
        awaitAdopted(wf, epoch, third);
        if (db.events(wf).stream().anyMatch(e -> e.type().equals("TIMER_SCHEDULED"))) {
            db.makePendingTimersDue(wf);
        }
        RollingDatabase.await("the rolled-forward run to finish", FINISH_TIMEOUT,
                () -> !ACTIVE.contains(db.status(wf).orElse("RUNNING")), () -> diagnostics(wf, third));
        finisher.assertFinished(third);
    }

    // ── Assertions on the persisted outcome ────────────────────────────────

    /**
     * {@code COMPLETED} with {@code output}, no failure or compensation event,
     * and each step run exactly once, live, on the node named for it.
     */
    private void assertCompletedCleanly(String wf, String output, Map<String, RollingNode> steps) {
        var diagnostics = diagnostics(wf);
        assertThat(db.status(wf)).as(diagnostics).contains("COMPLETED");
        assertThat(db.output(wf)).as(diagnostics).contains(output);
        assertThat(eventTypes(wf)).as(diagnostics).doesNotContain(
                "ACTIVITY_FAILED", "WORKFLOW_FAILED", "COMPENSATION_STARTED", "COMPENSATION_STEP_FAILED", "UNKNOWN");
        var expected = steps.entrySet().stream()
                .map(e -> new SideEffect(e.getKey(), "done", e.getValue().name()))
                .toList();
        assertThat(db.sideEffects(wf)).as("each step ran once, on the node expected\n" + diagnostics)
                .containsExactlyElementsOf(expected);
    }

    /**
     * {@code FAILED} by the saga's own failure ({@code ship}), both
     * compensations completed exactly once by {@code compensator}, the forward
     * steps once each on {@code forward}, and {@code refund} started on each
     * of {@code refundStarts} in turn (two entries: the at-least-once window of
     * an activity in flight when its node died).
     */
    private void assertCompensatedCleanly(String wf, RollingNode forward, RollingNode compensator,
                                          List<RollingNode> refundStarts) {
        var diagnostics = diagnostics(wf);
        assertThat(db.status(wf)).as(diagnostics).contains("FAILED");
        var events = db.events(wf);
        var failed = events.stream().filter(e -> e.type().equals("WORKFLOW_FAILED")).toList();
        assertThat(failed).as(diagnostics).hasSize(1);
        assertThat(failed.getFirst().payload()).as("the run failed by its own failure\n" + diagnostics)
                .contains("ship refused for " + wf);
        assertThat(eventTypes(wf)).as(diagnostics).doesNotContain("COMPENSATION_STEP_FAILED", "UNKNOWN");

        var shipNode = refundStarts.size() > 1 ? forward : compensator;
        var expected = new ArrayList<SideEffect>();
        expected.add(new SideEffect("reserve", "done", forward.name()));
        expected.add(new SideEffect("charge", "done", forward.name()));
        expected.add(new SideEffect("ship", "done", shipNode.name()));
        refundStarts.forEach(n -> expected.add(new SideEffect("refund", "started", n.name())));
        expected.add(new SideEffect("refund", "done", compensator.name()));
        expected.add(new SideEffect("release", "done", compensator.name()));
        assertThat(db.sideEffects(wf)).as("each step and compensation ran as expected\n" + diagnostics)
                .containsExactlyElementsOf(expected);
        assertThat(compensator.events(wf)).as("the compensating node reported the failure")
                .anyMatch(e -> e.callback().equals("workflowFailed"));
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

    private static Map<String, RollingNode> steps(String step1, RollingNode node1, String step2, RollingNode node2) {
        var steps = new LinkedHashMap<String, RollingNode>();
        steps.put(step1, node1);
        steps.put(step2, node2);
        return steps;
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

    private void result(Direction direction, String line) {
        var text = previousVersion + " | " + direction + " | " + scenario + " | " + line;
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
