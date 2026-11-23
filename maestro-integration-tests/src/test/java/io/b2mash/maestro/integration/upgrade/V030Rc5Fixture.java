package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Approval;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Invocations;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.OrderRequest;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.OrderResult;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Receipt;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedActivities;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedFailingActivities;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedFailingWorkflow;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedOrderActivities;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedOrderWorkflow;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedParkedSagaWorkflow;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedRiskActivities;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedSagaActivities;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedSagaWorkflow;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedTypedActivities;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.SeedTypedResultWorkflow;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Seeds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Seeds.COMPENSATING_CRASHED;
import static io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Seeds.CRASHED;
import static io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Seeds.PARKED_APPROVAL;
import static io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Seeds.PARKED_EXPRESS;
import static io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows.Seeds.PARKED_TIMER;

/**
 * The seeded history written by the build at tag {@code v0.3.0-rc.5}
 * ({@code 78fbdcac}), the fifth published pre-release: the same seeds as
 * {@code v0.3.0-rc.1} ({@code cbf5a2a}'s,
 * plus {@code seed-saga-parked} in a service of its own,
 * {@code SeededParkedSagaIT}), from the same frozen source. Captured by
 * {@code scripts/capture-upgrade-fixture.sh history v0.3.0-rc.5 v0_3_0_rc_5}.
 */
public final class V030Rc5Fixture implements SeededFixture {

    /** The one instance. */
    public static final V030Rc5Fixture INSTANCE = new V030Rc5Fixture();

    /**
     * SHA-256 of the raw bytes of {@code upgrade/v0_3_0_rc_5/seeded-history.sql} as
     * the capture at {@code v0.3.0-rc.5} wrote it (the value the capture script
     * printed). The row-level guards (counts, statuses, event types, the WP1
     * decimal) cannot see a hand-edited payload or timer row; this digest can.
     * Never update it without re-capturing under the regeneration rules.
     */
    public static final String DUMP_SHA256 = "c443866cf6139e841095b28f974de9a170df2bf3887bf8780dd500a2eba646c0";

    /**
     * SHA-256 of the raw bytes of {@code upgrade/v0_3_0_rc_5/capture.properties}
     * as committed with the dump. The manifest records the frozen workflow
     * source's path and SHA-256, and {@code theDumpWasCapturedAtItsRef} checks
     * that file's raw bytes against them; pinning the manifest therefore pins
     * the source too. Never update it without re-capturing under the
     * regeneration rules.
     */
    public static final String CAPTURE_MANIFEST_SHA256 = "3fd1d927e60e70b716b4b0132c5c6e5432f4d684217ca9a4cdee1bbd2fe30af1";

    /**
     * The seeded {@code PENDING} timer rows a replay needs <em>already due</em>:
     * {@code seed-parked-timer}'s 10-minute nap (fired by the timer poller) and
     * {@code seed-parked-express}'s 10-minute {@code express} deadline
     * (self-fired by {@code awaitSignal} during replay).
     */
    static final Set<String> MUST_BE_DUE = Set.of("seed-parked-timer/sleep-8002", "seed-parked-express/await-signal-8004");

    /** The latest {@code fire_at} among {@link #MUST_BE_DUE} ({@code seed-parked-express}'s {@code await-signal-8004}). */
    public static final Instant LATEST_MUST_BE_DUE = Instant.parse("2026-11-23T02:52:10.668143Z");

    /**
     * The earliest {@code fire_at} among every other seeded {@code PENDING}
     * timer row ({@code seed-parked-approval}'s 10-year {@code approval}
     * deadline; {@code seed-terminated}'s, the three {@code seed-wp1-*} ones and
     * {@code seed-saga-parked}'s follow within seconds). {@code getDueTimers} has no service filter, so
     * from this instant on a timer poller would fire them.
     */
    public static final Instant EARLIEST_MUST_STAY_PENDING = Instant.parse("2036-11-20T02:42:00.860795Z");

    /**
     * Every event {@code v0.3.0-rc.5} wrote, per seeded workflow (all 15 instances
     * in the dump): {@code "<seq>:<type>"} in sequence order. Derived from the
     * committed dump by raw SQL over the restored tables ({@code string_agg} of
     * {@code maestro_workflow_event} joined to {@code maestro_workflow_instance}),
     * never through the store, so it can catch a read path that drops,
     * reorders or retypes an event. Never edit.
     */
    static final Map<String, String> RECORDED_EVENTS = Map.ofEntries(
            Map.entry("seed-activity-failed",
                    "1:ACTIVITY_FAILED,2:WORKFLOW_FAILED"),
            Map.entry("seed-alert-failed",
                    "1:ACTIVITY_FAILED,2:ACTIVITY_COMPLETED,3:WORKFLOW_FAILED"),
            Map.entry("seed-compensating-crashed",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:ACTIVITY_FAILED,4:COMPENSATION_STARTED,"
                    + "5000:COMPENSATION_STEP_COMPLETED,5001:ACTIVITY_COMPLETED"),
            Map.entry("seed-completed",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED,8009:SIGNAL_RECEIVED,"
                    + "8010:ACTIVITY_COMPLETED,8011:SIGNAL_RECEIVED,8012:WORKFLOW_COMPLETED"),
            Map.entry("seed-crashed",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED,8009:SIGNAL_RECEIVED"),
            Map.entry("seed-parked-approval",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED"),
            Map.entry("seed-parked-express",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED"),
            Map.entry("seed-parked-timer",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED"),
            Map.entry("seed-saga-compensated",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:ACTIVITY_FAILED,4:COMPENSATION_STARTED,"
                    + "5000:COMPENSATION_STEP_COMPLETED,5001:ACTIVITY_COMPLETED,6000:COMPENSATION_STEP_COMPLETED,6001:ACTIVITY_COMPLETED,"
                    + "7001:COMPENSATION_COMPLETED,7002:WORKFLOW_FAILED"),
            Map.entry("seed-saga-parked",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:TIMER_SCHEDULED"),
            Map.entry("seed-saga-compensation-failed",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:ACTIVITY_FAILED,4:COMPENSATION_STARTED,"
                    + "5000:COMPENSATION_STEP_FAILED,5001:ACTIVITY_FAILED,6000:COMPENSATION_STEP_COMPLETED,6001:ACTIVITY_COMPLETED,"
                    + "7001:COMPENSATION_COMPLETED,7002:WORKFLOW_FAILED"),
            Map.entry("seed-terminated",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED"),
            Map.entry("seed-wp1-decimal",
                    "1:ACTIVITY_COMPLETED,2:TIMER_SCHEDULED"),
            Map.entry("seed-wp1-offset",
                    "1:ACTIVITY_COMPLETED,2:TIMER_SCHEDULED"),
            Map.entry("seed-wp1-set",
                    "1:ACTIVITY_COMPLETED,2:TIMER_SCHEDULED"));

    /**
     * What this build appends to each active run's history, after the events
     * {@code v0.3.0-rc.5} wrote: {@code "<seq> <type> <step>"}. Step 10's approval
     * is stored before the run reaches it, so it takes one slot; the saga's
     * second compensation goes to branch base 6000, as {@code v0.3.0-rc.5} placed
     * the first at 5000.
     */
    static final Map<String, List<String>> APPENDED = Map.of(
            PARKED_TIMER, List.of(
                    "8003 TIMER_FIRED $maestro:timer-fired",
                    "8004 TIMER_SCHEDULED $maestro:awaitSignal:express",
                    "8005 SIGNAL_TIMEOUT $maestro:awaitSignal:express",
                    "8006 TIMER_SCHEDULED $maestro:sleep",
                    "8007 TIMER_FIRED $maestro:timer-fired",
                    "8008 SIGNAL_RECEIVED $maestro:awaitSignal:approval",
                    "8009 ACTIVITY_COMPLETED seed-order.charge",
                    "8010 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8011 WORKFLOW_COMPLETED null"),
            PARKED_EXPRESS, List.of(
                    "8005 SIGNAL_TIMEOUT $maestro:awaitSignal:express",
                    "8006 TIMER_SCHEDULED $maestro:sleep",
                    "8007 TIMER_FIRED $maestro:timer-fired",
                    "8008 SIGNAL_RECEIVED $maestro:awaitSignal:approval",
                    "8009 ACTIVITY_COMPLETED seed-order.charge",
                    "8010 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8011 WORKFLOW_COMPLETED null"),
            PARKED_APPROVAL, List.of(
                    "8009 SIGNAL_RECEIVED $maestro:awaitSignal:approval",
                    "8010 ACTIVITY_COMPLETED seed-order.charge",
                    "8011 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8012 WORKFLOW_COMPLETED null"),
            CRASHED, List.of(
                    "8010 ACTIVITY_COMPLETED seed-order.charge",
                    "8011 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8012 WORKFLOW_COMPLETED null"),
            COMPENSATING_CRASHED, List.of(
                    "6000 COMPENSATION_STEP_COMPLETED seed-saga.releaseReservation",
                    "6001 ACTIVITY_COMPLETED seed-saga.releaseReservation",
                    "7001 COMPENSATION_COMPLETED $maestro:compensation",
                    "7002 WORKFLOW_FAILED null"));

    /** Finished runs, the three dormant WP1 runs and the parked saga (other services): ten. */
    static final List<String> UNTOUCHED_BY_RECOVERY = List.of(
            Seeds.COMPLETED_RUN, Seeds.TERMINATED_RUN, Seeds.SAGA_COMPENSATED, Seeds.SAGA_COMPENSATION_FAILED,
            Seeds.ACTIVITY_FAILED, Seeds.ALERT_FAILED, Seeds.WP1_DECIMAL, Seeds.WP1_OFFSET, Seeds.WP1_SET,
            Seeds.SAGA_PARKED);

    /**
     * What this build appends to {@code seed-saga-parked} after the three
     * events {@code v0.3.0-rc.5} wrote ({@code SeededParkedSagaIT}): the
     * signal, {@code ship}'s failure, then the LIFO unwind — entry 0
     * ({@code refund}) in block 7000 and entry 1 ({@code releaseReservation})
     * in block 8000, the blocks following the {@code COMPENSATION_STARTED}
     * slot as {@code cbf5a2a}'s sagas place theirs.
     */
    static final List<String> PARKED_SAGA_APPENDED = List.of(
            "4 SIGNAL_RECEIVED $maestro:awaitSignal:ship-now",
            "5 ACTIVITY_FAILED seed-saga.ship",
            "6 COMPENSATION_STARTED $maestro:compensation",
            "7000 COMPENSATION_STEP_COMPLETED seed-saga.refund",
            "7001 ACTIVITY_COMPLETED seed-saga.refund",
            "8000 COMPENSATION_STEP_COMPLETED seed-saga.releaseReservation",
            "8001 ACTIVITY_COMPLETED seed-saga.releaseReservation",
            "9001 COMPENSATION_COMPLETED $maestro:compensation",
            "9002 WORKFLOW_FAILED null");

    private V030Rc5Fixture() {
    }

    @Override
    public String name() {
        return "v0_3_0_rc_5";
    }

    @Override
    public String ref() {
        return "78fbdcacaa77518f919ba9cb8848ea15d52252ef";
    }

    @Override
    public String dumpSha256() {
        return DUMP_SHA256;
    }

    @Override
    public String captureManifestSha256() {
        return CAPTURE_MANIFEST_SHA256;
    }

    @Override
    public Set<String> mustBeDue() {
        return MUST_BE_DUE;
    }

    @Override
    public Instant latestMustBeDue() {
        return LATEST_MUST_BE_DUE;
    }

    @Override
    public Instant earliestMustStayPending() {
        return EARLIEST_MUST_STAY_PENDING;
    }

    @Override
    public List<String> appliedVersionsAtCapture() {
        return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
    }

    @Override
    public Map<String, String> recordedEvents() {
        return RECORDED_EVENTS;
    }

    @Override
    public Map<String, List<String>> appended() {
        return APPENDED;
    }

    @Override
    public List<String> untouchedByRecovery() {
        return UNTOUCHED_BY_RECOVERY;
    }

    @Override
    public List<String> parkedSagaAppended() {
        return PARKED_SAGA_APPENDED;
    }

    @Override
    public Map<String, WorkflowStatus> statusAtCapture() {
        return Seeds.STATUS_AT_CAPTURE;
    }

    @Override
    public Set<EventType> eventTypes() {
        return Seeds.EVENT_TYPES;
    }

    @Override
    public FrozenActivities activities(StepGate gate, boolean explodeFails) {
        var invocations = new Invocations();
        return new Activities(invocations, new SeedActivities(invocations, gate::enter, explodeFails));
    }

    @Override
    public Class<?> orderWorkflow() {
        return SeedOrderWorkflow.class;
    }

    @Override
    public Class<?> sagaWorkflow() {
        return SeedSagaWorkflow.class;
    }

    @Override
    public Class<?> failingWorkflow() {
        return SeedFailingWorkflow.class;
    }

    @Override
    public List<Class<?>> workflows() {
        return List.of(SeedOrderWorkflow.class, SeedSagaWorkflow.class, SeedFailingWorkflow.class,
                SeedTypedResultWorkflow.class, SeedParkedSagaWorkflow.class);
    }

    @Override
    public Object approval(String approver, int level) {
        return new Approval(approver, level);
    }

    @Override
    public Object orderRequest(String orderId, List<String> skus, long napMillis, long expressWaitMillis,
                               long cancellableNapMillis) {
        return new OrderRequest(orderId, skus, napMillis, expressWaitMillis, cancellableNapMillis);
    }

    @Override
    public Class<?> orderResultType() {
        return OrderResult.class;
    }

    @Override
    public Object orderResult(String orderId, String chargeId, BigDecimal amount, List<String> reservations,
                              boolean verified, boolean napCancelled, String note, String token, Instant decidedAt,
                              int version, String approver) {
        return new OrderResult(orderId, new Receipt(orderId, chargeId, amount), reservations, verified, napCancelled,
                note, token, decidedAt, version, approver);
    }

    @Override
    public String toString() {
        return name();
    }

    private record Activities(Invocations invocations, SeedActivities impl) implements FrozenActivities {

        @Override
        public void registerOn(MaestroEngineHarness node) {
            node.registerActivities(SeedOrderActivities.class, impl);
            node.registerActivities(SeedRiskActivities.class, impl);
            node.registerActivities(SeedSagaActivities.class, impl);
            node.registerActivities(SeedFailingActivities.class, impl);
            node.registerActivities(SeedTypedActivities.class, impl);
        }

        @Override
        public int count(String step) {
            return invocations.count(step);
        }

        @Override
        public Map<String, Integer> snapshot() {
            return invocations.snapshot();
        }
    }
}
