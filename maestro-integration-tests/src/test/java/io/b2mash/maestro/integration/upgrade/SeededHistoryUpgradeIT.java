package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static io.b2mash.maestro.core.model.WorkflowStatus.COMPLETED;
import static io.b2mash.maestro.core.model.WorkflowStatus.FAILED;
import static io.b2mash.maestro.core.model.WorkflowStatus.WAITING_SIGNAL;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.COMPENSATING_CRASHED;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.CRASHED;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.PARKED_APPROVAL;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.PARKED_EXPRESS;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.PARKED_TIMER;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * WP9's assertions over the history a past build wrote ({@link SeededHistory}),
 * run once per seeded-history fixture ({@link SeededFixtures#all()}: today
 * {@code cbf5a2a}, {@code v0_3_0_rc_1}, {@code v0_3_0_rc_2} and {@code v0_3_0_rc_3}): this build upgrades that database
 * without losing a row, reads every seeded workflow back, and — the replay
 * half — recovers, wakes and completes the runs the older build left active,
 * suppresses a duplicate of a signal id it stored and adopts the orphan
 * signals it stored, retries the failed runs it recorded, while leaving every
 * finished and other-service run exactly as written. Every other suite starts
 * from an empty schema and writes the history it replays with the same build,
 * so this is the only one that can catch a migration valid on an empty table
 * but fatal on existing rows, or a read or replay path that no longer
 * understands what an older build stored.
 *
 * <h2>What "resumes correctly" asserts, per parked state</h2>
 * <ul>
 *   <li><b>Timer wait</b> ({@code seed-parked-timer}, parked in a 10-minute
 *       {@code sleep}; {@code seed-parked-express}, parked in an
 *       {@code awaitSignal} whose 10-minute deadline has passed): the due timer
 *       row the older build left {@code PENDING} goes {@code FIRED}, the run
 *       replays every recorded step from the log (no recorded activity runs
 *       again), takes the rest live once each, and completes with the result
 *       the recorded values decide.</li>
 *   <li><b>Signal wait</b> ({@code seed-parked-approval}, parked on
 *       {@code approval}; the three runs above reach the same await): the
 *       signal delivered on this build is stored and consumed under the run's
 *       instance, and so is the {@code note} the older build stored before the
 *       run reached it.</li>
 *   <li><b>Mid-activity crash</b> ({@code seed-crashed}, {@code charge} in
 *       flight at the dump): the unrecorded step runs once on this build.</li>
 *   <li><b>Compensating</b> ({@code seed-compensating-crashed}, recovered with
 *       one compensation recorded and the second in flight): the recorded
 *       compensation is not run again, the other runs once, and the run ends
 *       {@code FAILED} by its own failure.</li>
 *   <li><b>Failed, awaiting Retry</b> ({@code seed-activity-failed},
 *       {@code seed-alert-failed}): a Retry on this build reopens the run and
 *       it completes; the compensated saga is refused as
 *       {@code COMPENSATED_NOT_RETRYABLE}.</li>
 *   <li><b>Saga parked mid-flight</b> (a saga parked on a signal with its
 *       compensations registered): only in fixtures that seed one; see
 *       {@code SeededParkedSagaIT}.</li>
 * </ul>
 * <p>In every case: the older build's events are an unchanged prefix of the
 * final history (whole-record equality), this build appends exactly
 * {@link SeededFixture#appended()}, nothing stands down and nothing is
 * abandoned, and every appended {@code ACTIVITY_COMPLETED} carries an argument
 * fingerprint.
 *
 * <p>The workflow ids below come from {@code cbf5a2a}'s {@code Seeds}: every
 * later fixture keeps every {@code cbf5a2a} seed under the same id
 * ({@link #everyFixtureHoldsEveryCbf5a2aSeed}). The frozen types each fixture
 * defines are reached through {@link SeededFixture}.
 *
 * <p>This class uses AssertJ and the plan's method names although
 * {@code maestro-integration-tests/SPEC.md} asks for plain JUnit assertions and
 * {@code subject_expectation} names: a recorded waiver, because the tests are
 * the WP9 brief's verbatim code and Task 4 extends this same class — do not
 * "fix" it.
 *
 * <h2>Pins</h2>
 * <p>Later work packages add assertions here and may deliberately change what
 * the engine <em>appends</em> on top of old history. Every pin below is one of
 * two kinds; know which before editing one.
 *
 * <p><b>Never edit</b> — these describe history a past build wrote. If one
 * goes red, this build broke reading or replaying old history; fix the engine,
 * never the pin.
 * <ul>
 *   <li>Each fixture's {@link SeededFixture#dumpSha256()} and
 *       {@link SeededFixture#captureManifestSha256()}, and every assertion of
 *       {@link #theDumpWasCapturedAtItsRef}: the manifest's {@code ref} and
 *       {@code serverVersion}, the frozen source against
 *       {@code fixtureSource.sha256}, no {@code psql} meta-command.</li>
 *   <li>{@link #theSeededDatabaseUpgradesUnderThisBuild}: the schema versions
 *       recorded before the upgrade ({@link SeededFixture#appliedVersionsAtCapture()};
 *       afterwards still that prefix) and the row counts
 *       ({@code rowCounts} unchanged by the upgrade).</li>
 *   <li>{@link SeededFixture#recordedEvents()} and
 *       {@link #eachSeededWorkflowsEventsAreExactlyTheOnesItsBuildWrote}: every
 *       seeded workflow's events ({@code "<seq>:<type>"}), as raw rows and
 *       through the store.</li>
 *   <li>{@link #everySeededWorkflowReadsBackThroughThisBuildsStore}: the
 *       statuses and event types at capture ({@code Seeds.STATUS_AT_CAPTURE},
 *       {@code Seeds.EVENT_TYPES}, which live in the frozen source) and the
 *       raw decimal {@code 12345678901234567890.123456789} in
 *       {@code seed-wp1-decimal}'s stored payload.</li>
 *   <li>In {@link #recoveryReplaysEveryActiveSeededRunToItsEnd}: the
 *       whole-record equality of the pre-existing event prefix
 *       ({@code after.subList(0, recorded.size())} equals {@code recorded}),
 *       each run in {@link #ACTIVE} reaching its end ({@code COMPLETED}, the
 *       compensating saga {@code FAILED}) with {@code observer.standDowns()}
 *       and {@code observer.abandoned()} empty — a run a past build left
 *       active that no longer finishes, or stands down, is broken replay —
 *       and, inside the expected {@code OrderResult}, the values read from
 *       recorded history ({@code $maestro:randomUUID},
 *       {@code $maestro:currentTime}, {@code CRASHED}'s approver
 *       {@code "capture"}).</li>
 *   <li>{@link #finishedAndOtherServiceRunsAreNotAdopted}, except the two
 *       lines named in the second list: the untouched runs
 *       ({@link SeededFixture#untouchedByRecovery()}), their PENDING timers
 *       (the teeth of the comparison), and their whole instance rows, events,
 *       signal rows and timer rows ({@code wholeRows}, {@code events}) equal
 *       before and after; the three {@code seed-wp1-*} runs still
 *       {@code WAITING_SIGNAL}.</li>
 *   <li>{@link #aSignalIdStoredByTheOlderBuildStillSuppressesItsDuplicate}: the
 *       dedup row — {@code deliverSignal} returns {@code false} and the one
 *       row the older build stored stays, unconsumed.</li>
 *   <li>In {@link #aWorkflowStartedOnThisBuildAdoptsOrphanSignalsTheOlderBuildStored}:
 *       the approver {@code "capture"} and the note, read from the orphan rows
 *       the older build stored.</li>
 *   <li>Each fixture's clock window ({@link SeededFixture#mustBeDue()},
 *       {@link SeededFixture#latestMustBeDue()},
 *       {@link SeededFixture#earliestMustStayPending()}), which
 *       {@code requireClockWithinFixtureWindow} checks against the seeded
 *       timer rows.</li>
 * </ul>
 *
 * <p><b>Current-build behaviour</b> — these describe what <em>this</em> build
 * does on top of old history. A fix that deliberately changes engine
 * behaviour may change them, only as a named step of its plan, with the
 * reason in the commit message.
 * <ul>
 *   <li>{@link SeededFixture#appended()}: the events appended to each active run.</li>
 *   <li>In {@link #recoveryReplaysEveryActiveSeededRunToItsEnd}: the
 *       invocation counts ({@code activities.snapshot()}, both
 *       {@code containsAllEntriesOf} maps), the {@code live} call list, the
 *       derived {@code OrderResult} ({@code expected}: receipt, charge id and
 *       level, the flags, {@code "upgrade-node"} as approver), the timer rows
 *       {@code sleep-8002} and {@code await-signal-8004} going {@code FIRED}
 *       ({@code timerStatus}), and the consumed signal rows
 *       ({@code signalRows}: {@code consumed=true} under the run's
 *       instance).</li>
 *   <li>{@code node.recover()} returning 5, in
 *       {@link #recoveryReplaysEveryActiveSeededRunToItsEnd} and
 *       {@link #finishedAndOtherServiceRunsAreNotAdopted}, and in the latter
 *       {@code lease_epoch=1} on every active run.</li>
 *   <li>{@code assertThatEveryActivityCompletedIsFingerprinted}: every
 *       appended {@code ACTIVITY_COMPLETED} carries a non-null
 *       {@code argsHash}.</li>
 *   <li>In {@link #aWorkflowStartedOnThisBuildAdoptsOrphanSignalsTheOlderBuildStored}:
 *       the run completing, its charge id {@code charge:seed-orphan-target:1},
 *       and the consumed signal rows ({@code signalRows}).</li>
 *   <li>In {@link #theFailedRunsTheOlderBuildRecordedAreRetriedByThisBuild}: each outcome, status, the appended events, and the invocation counts.</li>
 * </ul>
 * {@link #theJvmRunsInTheZoneTheNonUtcTaskAsksFor} guards the test setup, not
 * the fixture, and is neither.
 */
@Tag("integration")
@ParameterizedClass(name = "Seeded history written by {0}, under this build")
@MethodSource("fixtures")
class SeededHistoryUpgradeIT {

    private static final List<String> TABLES = List.of(
            "maestro_workflow_instance", "maestro_workflow_event",
            "maestro_workflow_signal", "maestro_workflow_timer");

    /** Set by the {@code seededHistoryNonUtcTest} Gradle task to the zone its JVM runs in. */
    private static final String EXPECTED_ZONE_PROPERTY = "maestro.test.expectedZone";

    /** The five runs every fixture leaves active in {@code Seeds.SERVICE}: the ones recovery adopts. */
    private static final List<String> ACTIVE = List.of(
            PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL, CRASHED, COMPENSATING_CRASHED);

    static Stream<SeededFixture> fixtures() {
        return SeededFixtures.all().stream();
    }

    private final SeededFixture fixture;

    SeededHistoryUpgradeIT(SeededFixture fixture) {
        this.fixture = fixture;
    }

    @Test
    @DisplayName("the fixture keeps every cbf5a2a seed under its id and status, and is listed once")
    void everyFixtureHoldsEveryCbf5a2aSeed() {
        assertThat(fixture.statusAtCapture()).containsAllEntriesOf(Cbf5a2aFixture.INSTANCE.statusAtCapture());
        assertThat(fixture.untouchedByRecovery()).containsAll(Cbf5a2aFixture.INSTANCE.untouchedByRecovery())
                .doesNotContainAnyElementsOf(ACTIVE);
        assertThat(fixture.appended()).containsOnlyKeys(ACTIVE);
        assertThat(SeededFixtures.all().stream().map(SeededFixture::name)).containsOnlyOnce(fixture.name());
    }

    @Test
    @DisplayName("The dump was captured at its ref, from the frozen fixture source, with no psql meta-commands")
    void theDumpWasCapturedAtItsRef() throws Exception {
        var seeded = SeededHistory.load(fixture);
        // First: the manifest pins the frozen source (its path and SHA-256), so a manifest edit is
        // reported as one, not as a source-hash mismatch.
        assertThat(sha256(resourceBytes(fixture.manifestResource())))
                .as("capture.properties is frozen: byte for byte what the capture at %s wrote", fixture.name())
                .isEqualTo(fixture.captureManifestSha256());
        assertThat(seeded.manifest().getProperty("ref")).isEqualTo(fixture.ref());
        assertThat(seeded.manifest().getProperty("serverVersion")).startsWith("16.");
        assertThat(seeded.manifest().getProperty("fixtureSource.path"))
                .as("the manifest names the fixture's own frozen package")
                .isEqualTo("src/test/java/io/b2mash/maestro/integration/upgrade/" + fixture.name()
                        + "/SeededHistoryWorkflows.java");
        assertThat(sha256(Files.readAllBytes(seeded.fixtureSource())))
                .as("SeededHistoryWorkflows.java is frozen: it produced the seeded history")
                .isEqualTo(seeded.manifest().getProperty("fixtureSource.sha256"));
        assertThat(dumpLines()).noneMatch(line -> line.startsWith("\\"));
        assertThat(sha256(resourceBytes(fixture.dumpResource())))
                .as("seeded-history.sql is frozen: byte for byte what the capture at %s wrote", fixture.name())
                .isEqualTo(fixture.dumpSha256());
    }

    @Test
    @DisplayName("This build's migrations upgrade the seeded database, validate over it, and keep every row")
    void theSeededDatabaseUpgradesUnderThisBuild() throws Exception {
        var seeded = SeededHistory.load(fixture);
        var before = rowCounts(seeded);          // maestro_workflow_{instance,event,signal,timer}
        var atCapture = fixture.appliedVersionsAtCapture().toArray(String[]::new);
        assertThat(appliedVersions(seeded)).containsExactly(atCapture);
        seeded.upgrade(SchemaMode.FLYWAY);
        seeded.upgrade(SchemaMode.VALIDATE);     // throws if anything this build ships is unrecorded or edited
        assertThat(appliedVersions(seeded)).startsWith(atCapture);
        assertThat(rowCounts(seeded)).isEqualTo(before);
    }

    @Test
    @DisplayName("Every seeded workflow reads back through this build's store with its status and event types")
    void everySeededWorkflowReadsBackThroughThisBuildsStore() throws Exception {
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY);
        fixture.statusAtCapture().forEach((id, status) ->
                assertThat(seeded.store().getInstance(id)).get().extracting(WorkflowInstance::status).as(id)
                        .isEqualTo(status));
        var types = fixture.statusAtCapture().keySet().stream().flatMap(id -> seeded.events(id).stream())
                .map(WorkflowEvent::eventType).collect(toCollection(() -> EnumSet.noneOf(EventType.class)));
        assertThat(types).isEqualTo(fixture.eventTypes());          // includes: no UNKNOWN
        // WP1 input: the store wrote the decimal exactly (raw JSONB text of seed-wp1-decimal's ACTIVITY_COMPLETED payload)
        assertThat(rawPayload(seeded, Seeds.WP1_DECIMAL, "ACTIVITY_COMPLETED")).contains("12345678901234567890.123456789");
    }

    @Test
    @DisplayName("each seeded workflow's events, as raw rows and through this build's store, are exactly the ones its build wrote")
    void eachSeededWorkflowsEventsAreExactlyTheOnesItsBuildWrote() throws Exception {
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY);
        var recordedEvents = fixture.recordedEvents();
        assertThat(recordedEvents.keySet()).isEqualTo(fixture.statusAtCapture().keySet());
        assertThat(rawEvents(seeded)).as("raw rows of every instance in the database").isEqualTo(recordedEvents);
        recordedEvents.forEach((id, recorded) -> assertThat(seeded.events(id).stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType()).collect(joining(",")))
                .as("%s through this build's store", id).isEqualTo(recorded));
    }

    // ── replay half: this build drives the history ────────────────────────

    @Test
    @DisplayName("recovery on this build replays every active run the older build left and drives it to its end")
    void recoveryReplaysEveryActiveSeededRunToItsEnd() throws Exception {
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var observer = new RecordingObserver();
        var before = new LinkedHashMap<String, List<WorkflowEvent>>();
        ACTIVE.forEach(id -> before.put(id, seeded.events(id)));
        // Every call this build makes is recorded as "<step>:<key>", so a test can tell which run a live
        // call belonged to.
        var live = new CopyOnWriteArrayList<String>();
        var invocations = fixture.activities((step, key) -> live.add(step + ":" + key), true);

        try (var node = seeded.node(Seeds.SERVICE, observer, invocations)) {
            assertThat(node.recover()).isEqualTo(5);   // parked-timer, parked-express, parked-approval, crashed, compensating-crashed
            // The approvals are stored before the timer poller starts. parked-timer needs the poller for
            // its seeded nap (sleep-8002); parked-express self-fires its seeded express deadline during
            // replay but then needs the poller for its 1 ms nap (sleep() never self-fires). So neither
            // reaches step 10 before its approval is stored, and step 10 always takes exactly one slot.
            for (var id : List.of(PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL)) {
                node.deliverSignal(id, "approval", fixture.approval("upgrade-node", 2));
            }
            node.startTimerPoller(Duration.ofMillis(200), 50);
            for (var id : List.of(PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL, CRASHED)) {
                assertThat(seeded.awaitStatusOrLast(id, COMPLETED, Duration.ofSeconds(60)))
                        .as("%s; stand-downs: %s", id, observer.standDowns()).isEqualTo(COMPLETED);
            }
            assertThat(seeded.awaitStatusOrLast(COMPENSATING_CRASHED, FAILED, Duration.ofSeconds(60)))
                    .as("%s; stand-downs: %s", COMPENSATING_CRASHED, observer.standDowns()).isEqualTo(FAILED);

            // every run has finished: nothing stood down or was abandoned on the way
            assertThat(observer.standDowns()).isEmpty();
            assertThat(observer.abandoned()).isEmpty();
            // recorded steps came from the log, unrecorded ones ran exactly once:
            assertThat(invocations.snapshot()).containsAllEntriesOf(Map.of(
                    "seed-order.quote", 0, "seed-order.reserve", 0, "seed-risk.verify", 0, "seed-order.charge", 4,
                    "seed-saga.reserve", 0, "seed-saga.charge", 0, "seed-saga.ship", 0, "seed-saga.refund", 0,
                    "seed-saga.releaseReservation", 1));   // snapshot() has an entry for every Seeds.STEPS key
            assertThat(invocations.snapshot()).as("the FAILED and WP1 runs ran nothing")
                    .containsAllEntriesOf(Map.of("seed-failing.explode", 0, "seed-failing.alert", 0,
                            "seed-typed.exactAmount", 0, "seed-typed.localStamp", 0, "seed-typed.tags", 0));
            // ... and each live call belongs to the run whose step the older build never recorded
            assertThat(live).containsExactlyInAnyOrder(
                    "seed-order.charge:" + PARKED_TIMER, "seed-order.charge:" + PARKED_EXPRESS,
                    "seed-order.charge:" + PARKED_APPROVAL, "seed-order.charge:" + CRASHED,
                    "seed-saga.releaseReservation:crash-compensation");
        }

        for (var id : ACTIVE) {
            var after = seeded.events(id);
            var recorded = before.get(id);
            assertThat(after.size()).as(id).isGreaterThan(recorded.size());
            assertThat(after.subList(0, recorded.size())).as("%s: history %s wrote is untouched", id, fixture.name())
                    .isEqualTo(recorded);
            var appended = after.subList(recorded.size(), after.size());
            assertThat(appended.stream().map(SeededHistoryUpgradeIT::describe))
                    .as("%s: events this build appended", id).containsExactlyElementsOf(fixture.appended().get(id));
            assertThatEveryActivityCompletedIsFingerprinted(id, appended);
        }
        for (var id : List.of(PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL, CRASHED)) {
            var recorded = before.get(id);
            var level = id.equals(CRASHED) ? 1 : 2;
            var expected = fixture.orderResult(id, "charge:" + id + ":" + level, new BigDecimal("151.98"),
                    List.of("reservation:sku-red", "reservation:sku-blue"), false,
                    id.equals(PARKED_APPROVAL) || id.equals(CRASHED), "note for " + id,
                    seeded.serializer().deserialize(recordedPayload(recorded, "$maestro:randomUUID"), String.class),
                    seeded.serializer().deserialize(recordedPayload(recorded, "$maestro:currentTime"), Instant.class),
                    1, id.equals(CRASHED) ? "capture" : "upgrade-node");
            assertThat(orderResult(seeded, id)).as(id).isEqualTo(expected);
        }
        // the due rows the older build left PENDING are FIRED on this build (sleep-8002 by the timer poller,
        // await-signal-8004 by awaitSignal's self-fire during replay); every Order signal it stored was consumed
        assertThat(timerStatus(seeded, PARKED_TIMER, "sleep-8002")).isEqualTo("FIRED");
        assertThat(timerStatus(seeded, PARKED_EXPRESS, "await-signal-8004")).isEqualTo("FIRED");
        for (var id : List.of(PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL, CRASHED)) {
            var instance = instanceId(seeded, id);
            assertThat(signalRows(seeded, id)).as(id).containsExactlyInAnyOrder(
                    "approval consumed=true instance=" + instance, "note consumed=true instance=" + instance);
        }
    }

    @Test
    @DisplayName("finished and other-service runs are left exactly as the older build wrote them")
    void finishedAndOtherServiceRunsAreNotAdopted() throws Exception {
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var untouched = fixture.statusAtCapture().keySet().stream().filter(id -> !ACTIVE.contains(id)).toList();
        // cbf5a2a: completed, terminated, four FAILED, three seed-wp1-*; a later fixture may add other-service runs
        assertThat(untouched).containsExactlyInAnyOrderElementsOf(fixture.untouchedByRecovery());
        var rowsBefore = wholeRows(seeded, "maestro_workflow_instance", untouched);
        var eventsBefore = new LinkedHashMap<String, List<WorkflowEvent>>();
        untouched.forEach(id -> eventsBefore.put(id, seeded.events(id)));
        var signalsBefore = wholeRows(seeded, "maestro_workflow_signal", untouched);
        var timersBefore = wholeRows(seeded, "maestro_workflow_timer", untouched);
        // the comparison has teeth: these runs hold PENDING timers a wrongly adopting node would touch
        for (var id : List.of(Seeds.TERMINATED_RUN, Seeds.WP1_DECIMAL, Seeds.WP1_OFFSET, Seeds.WP1_SET)) {
            assertThat(timersBefore.get(id)).as(id).anyMatch(row -> row.contains(",PENDING,"));
        }
        var observer = new RecordingObserver();

        try (var node = seeded.node(Seeds.SERVICE, observer, fixture.activities(SeededFixture.StepGate.OPEN, true))) {
            var recovered = node.recover();
            // the two runs that need nothing from the test finish, so no write of this node is still pending
            assertThat(seeded.awaitStatusOrLast(CRASHED, COMPLETED, Duration.ofSeconds(60)))
                    .as("%s; stand-downs: %s", CRASHED, observer.standDowns()).isEqualTo(COMPLETED);
            assertThat(seeded.awaitStatusOrLast(COMPENSATING_CRASHED, FAILED, Duration.ofSeconds(60)))
                    .as("%s; stand-downs: %s", COMPENSATING_CRASHED, observer.standDowns()).isEqualTo(FAILED);

            assertThat(wholeRows(seeded, "maestro_workflow_instance", untouched)).isEqualTo(rowsBefore);
            untouched.forEach(id -> assertThat(seeded.events(id)).as(id).isEqualTo(eventsBefore.get(id)));
            assertThat(wholeRows(seeded, "maestro_workflow_signal", untouched)).isEqualTo(signalsBefore);
            assertThat(wholeRows(seeded, "maestro_workflow_timer", untouched)).isEqualTo(timersBefore);
            for (var id : List.of(Seeds.WP1_DECIMAL, Seeds.WP1_OFFSET, Seeds.WP1_SET)) {
                assertThat(seeded.store().getInstance(id)).get().extracting(WorkflowInstance::status).as(id)
                        .isEqualTo(WAITING_SIGNAL);
            }
            // positively: exactly the five active runs were adopted, each bumping its lease epoch once
            assertThat(recovered).isEqualTo(5);
            assertThat(instanceRows(seeded, ACTIVE).values()).allMatch(row -> row.endsWith(" lease_epoch=1"));
            assertThat(observer.standDowns()).isEmpty();
            assertThat(observer.abandoned()).isEmpty();
        }
    }

    @Test
    @DisplayName("a signal id stored by the older build still suppresses its duplicate")
    void aSignalIdStoredByTheOlderBuildStillSuppressesItsDuplicate() throws Exception {
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY);
        try (var node = seeded.node(Seeds.SERVICE, new RecordingObserver(),
                fixture.activities(SeededFixture.StepGate.OPEN, true))) {
            assertThat(node.executor().deliverSignal(PARKED_APPROVAL, "note", "duplicate",
                    "client:" + PARKED_APPROVAL + ":note")).isFalse();
        }
        assertThat(signalPayloads(seeded, PARKED_APPROVAL, "note"))
                .as("only the row %s stored, still unconsumed", fixture.name())
                .containsExactly("\"note for seed-parked-approval\" consumed=false");
    }

    @Test
    @DisplayName("a workflow started on this build adopts the orphan signals the older build stored for it")
    void aWorkflowStartedOnThisBuildAdoptsOrphanSignalsTheOlderBuildStored() throws Exception {
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var observer = new RecordingObserver();
        try (var node = seeded.node(Seeds.SERVICE, observer, fixture.activities(SeededFixture.StepGate.OPEN, true))) {
            node.startTimerPoller(Duration.ofMillis(200), 50);
            node.start(Seeds.ORPHAN_TARGET, fixture.orderWorkflow(),
                    fixture.orderRequest(Seeds.ORPHAN_TARGET, List.of("sku-1"), 1, 1, 1));
            assertThat(seeded.awaitStatusOrLast(Seeds.ORPHAN_TARGET, COMPLETED, Duration.ofSeconds(60)))
                    .as("%s; stand-downs: %s", Seeds.ORPHAN_TARGET, observer.standDowns()).isEqualTo(COMPLETED);
            assertThat(observer.standDowns()).isEmpty();
            assertThat(observer.abandoned()).isEmpty();
        }
        assertThatEveryActivityCompletedIsFingerprinted(Seeds.ORPHAN_TARGET, seeded.events(Seeds.ORPHAN_TARGET));
        var result = orderResultTree(seeded, Seeds.ORPHAN_TARGET);
        assertThat(result.get("approver").asString()).isEqualTo("capture");
        assertThat(result.get("note").asString()).isEqualTo("note for seed-orphan-target");
        assertThat(result.get("receipt").get("chargeId").asString()).isEqualTo("charge:seed-orphan-target:1");
        // Both rows the older build stored with no instance now belong to this build's run, and were consumed by it.
        // This is the assertion that guards adoption: the consume path finds signals by workflow id, so a
        // build that adopted nothing would still complete with approver "capture" (seen in the mutation check).
        var instanceId = instanceId(seeded, Seeds.ORPHAN_TARGET);
        assertThat(signalRows(seeded, Seeds.ORPHAN_TARGET)).containsExactlyInAnyOrder(
                "approval consumed=true instance=" + instanceId, "note consumed=true instance=" + instanceId);
    }

    @Test
    @DisplayName("the FAILED runs the older build recorded are retried by this build (WP3)")
    void theFailedRunsTheOlderBuildRecordedAreRetriedByThisBuild() throws Exception {
        for (var id : List.of(Seeds.ACTIVITY_FAILED, Seeds.ALERT_FAILED)) {
            var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
            var observer = new RecordingObserver();
            var invocations = fixture.activities(SeededFixture.StepGate.OPEN, false);
            try (var node = seeded.node(Seeds.SERVICE, observer, invocations)) {
                assertThat(node.retry(id, fixture.failingWorkflow())).as(id).isEqualTo(WorkflowExecutor.RetryOutcome.RETRIED);
                assertThat(seeded.awaitStatusOrLast(id, COMPLETED, Duration.ofSeconds(30)))
                        .as("%s; stand-downs: %s", id, observer.standDowns()).isEqualTo(COMPLETED);
                assertThat(seeded.events(id).stream().map(e -> e.sequenceNumber() + " " + e.eventType() + " " + e.stepName()).toList())
                        .as(id).containsExactly("1 ACTIVITY_COMPLETED seed-failing.explode", "2 WORKFLOW_COMPLETED null");
                assertThat(invocations.count("seed-failing.explode")).as(id).isEqualTo(1);
                assertThat(invocations.count("seed-failing.alert")).as(id).isEqualTo(0);
                assertThat(observer.standDowns()).as(id).isEmpty();
            }
        }
        var saga = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        try (var node = saga.node(Seeds.SERVICE, new RecordingObserver(),
                fixture.activities(SeededFixture.StepGate.OPEN, false))) {
            assertThat(node.retry(Seeds.SAGA_COMPENSATED, fixture.sagaWorkflow()))
                    .isEqualTo(WorkflowExecutor.RetryOutcome.COMPENSATED_NOT_RETRYABLE);
        }
    }

    @Test
    @DisplayName("under seededHistoryNonUtcTest the test JVM really runs in the non-UTC zone that task asks for")
    void theJvmRunsInTheZoneTheNonUtcTaskAsksFor() {
        var expected = System.getProperty(EXPECTED_ZONE_PROPERTY);
        assumeThat(expected).as("%s is set only by the seededHistoryNonUtcTest task", EXPECTED_ZONE_PROPERTY)
                .isNotNull();
        assertThat(ZoneId.systemDefault()).as("JVM default zone (user.timezone=%s)", System.getProperty("user.timezone"))
                .isEqualTo(ZoneId.of(expected));
        assertThat(ZoneId.systemDefault().getRules().getOffset(Instant.now())).isNotEqualTo(ZoneOffset.UTC);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Every {@code ACTIVITY_COMPLETED} carries a SHA-256 argument fingerprint a later build can verify. */
    private static void assertThatEveryActivityCompletedIsFingerprinted(String workflowId, List<WorkflowEvent> events) {
        var completed = events.stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).toList();
        assertThat(completed).as("%s: ACTIVITY_COMPLETED events", workflowId).isNotEmpty();
        assertThat(completed).as("%s: argsHash of every ACTIVITY_COMPLETED", workflowId)
                .allSatisfy(e -> assertThat(e.argsHash()).as(describe(e)).matches("[0-9a-f]{64}"));
    }

    /** {@code workflow_id -> every row of table, whole, as Postgres renders it}, sorted by row id. */
    private static Map<String, List<String>> wholeRows(SeededHistory seeded, String table, List<String> workflowIds)
            throws SQLException {
        var rows = new LinkedHashMap<String, List<String>>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("SELECT t::text FROM " + table + " t WHERE workflow_id = ? ORDER BY id")) {
            for (var id : workflowIds) {
                ps.setString(1, id);
                var forId = new ArrayList<String>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        forId.add(rs.getString(1));
                    }
                }
                rows.put(id, forId);
            }
        }
        return rows;
    }

    private static String describe(WorkflowEvent event) {
        return event.sequenceNumber() + " " + event.eventType() + " " + event.stepName();
    }

    /** The payload of the one recorded event of {@code stepName}. */
    private static JsonNode recordedPayload(List<WorkflowEvent> events, String stepName) {
        var matching = events.stream().filter(e -> stepName.equals(e.stepName())).toList();
        assertThat(matching).as(stepName).hasSize(1);
        return Objects.requireNonNull(matching.getFirst().payload(), stepName);
    }

    /** The run's output read as the fixture's own frozen {@code OrderResult}, so {@code equals} compares every field. */
    private Object orderResult(SeededHistory seeded, String workflowId) {
        var output = seeded.store().getInstance(workflowId).orElseThrow().output();
        return Objects.requireNonNull(seeded.serializer().deserialize(output, fixture.orderResultType()), workflowId);
    }

    /** The run's output as a JSON tree. */
    private static JsonNode orderResultTree(SeededHistory seeded, String workflowId) {
        var output = seeded.store().getInstance(workflowId).orElseThrow().output();
        return Objects.requireNonNull(output, workflowId);
    }

    /** {@code workflow_id -> "<status> version=<v> lease_epoch=<e>"}, as stored. */
    private static Map<String, String> instanceRows(SeededHistory seeded, List<String> workflowIds) throws SQLException {
        var rows = new LinkedHashMap<String, String>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("SELECT status, version, lease_epoch FROM maestro_workflow_instance"
                     + " WHERE workflow_id = ?")) {
            for (var id : workflowIds) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).as(id).isTrue();
                    rows.put(id, rs.getString(1) + " version=" + rs.getInt(2) + " lease_epoch=" + rs.getLong(3));
                }
            }
        }
        return rows;
    }

    private static String timerStatus(SeededHistory seeded, String workflowId, String timerId) throws SQLException {
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("SELECT status FROM maestro_workflow_timer"
                     + " WHERE workflow_id = ? AND timer_id = ?")) {
            ps.setString(1, workflowId);
            ps.setString(2, timerId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s timer %s", workflowId, timerId).isTrue();
                return rs.getString(1);
            }
        }
    }

    /** Every signal row of the workflow: {@code "<name> consumed=<b> instance=<workflow_instance_id>"}. */
    private static List<String> signalRows(SeededHistory seeded, String workflowId) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("SELECT signal_name, consumed, workflow_instance_id"
                     + " FROM maestro_workflow_signal WHERE workflow_id = ?")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " consumed=" + rs.getBoolean(2) + " instance=" + rs.getObject(3));
                }
            }
        }
        return rows;
    }

    private static String instanceId(SeededHistory seeded, String workflowId) {
        return seeded.store().getInstance(workflowId).orElseThrow().id().toString();
    }

    /** Every {@code signalName} row of the workflow: {@code "<payload> consumed=<b>"}. */
    private static List<String> signalPayloads(SeededHistory seeded, String workflowId, String signalName)
            throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("SELECT payload::text, consumed FROM maestro_workflow_signal"
                     + " WHERE workflow_id = ? AND signal_name = ? ORDER BY received_at")) {
            ps.setString(1, workflowId);
            ps.setString(2, signalName);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " consumed=" + rs.getBoolean(2));
                }
            }
        }
        return rows;
    }

    private static Map<String, Long> rowCounts(SeededHistory seeded) throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        try (var conn = seeded.dataSource().getConnection();
             var st = conn.createStatement()) {
            for (var table : TABLES) {
                try (var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        }
        return counts;
    }

    private static List<String> appliedVersions(SeededHistory seeded) throws SQLException {
        var versions = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT version FROM maestro_schema_history"
                     + " WHERE type = 'SQL' AND success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    /** {@code workflow_id -> "<seq>:<type>,..."} for every instance in the database, by raw SQL. */
    private static Map<String, String> rawEvents(SeededHistory seeded) throws SQLException {
        var events = new LinkedHashMap<String, String>();
        try (var conn = seeded.dataSource().getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("""
                     SELECT i.workflow_id,
                            COALESCE(string_agg(e.sequence_number || ':' || e.event_type, ',' ORDER BY e.sequence_number), '')
                     FROM maestro_workflow_instance i
                     LEFT JOIN maestro_workflow_event e ON e.workflow_instance_id = i.id
                     GROUP BY i.workflow_id""")) {
            while (rs.next()) {
                events.put(rs.getString(1), rs.getString(2));
            }
        }
        return events;
    }

    /** The raw JSONB text of the workflow's one event of {@code eventType}, as Postgres holds it. */
    private static String rawPayload(SeededHistory seeded, String workflowId, String eventType) throws SQLException {
        var payloads = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("""
                     SELECT e.payload::text FROM maestro_workflow_event e
                     JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id
                     WHERE i.workflow_id = ? AND e.event_type = ?
                     ORDER BY e.sequence_number""")) {
            ps.setString(1, workflowId);
            ps.setString(2, eventType);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    payloads.add(rs.getString(1));
                }
            }
        }
        assertThat(payloads).as("%s events of %s", eventType, workflowId).hasSize(1);
        return payloads.getFirst();
    }

    private List<String> dumpLines() throws Exception {
        try (var in = Objects.requireNonNull(
                SeededHistoryUpgradeIT.class.getClassLoader().getResourceAsStream(fixture.dumpResource()),
                fixture.dumpResource());
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    /** The raw bytes of a test-classpath resource. */
    private static byte[] resourceBytes(String resource) throws Exception {
        try (var in = Objects.requireNonNull(
                SeededHistoryUpgradeIT.class.getClassLoader().getResourceAsStream(resource), resource)) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
