package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.TimerInfo;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level pin for the design doc §2.2 meter catalog: every row's name,
 * type, tags, and increment-on-live-callback behaviour, plus the
 * unit-level half of the replay pin — no increment when {@code replayed}.
 *
 * <p>Uses a bare {@link SimpleMeterRegistry}, bypassing Spring entirely, so
 * every callback→meter mapping is proven directly against
 * {@link MicrometerEngineObserver} without the auto-configuration chain
 * (that chain is covered separately by
 * {@link MaestroObservabilityAutoConfigurationTest}).
 */
@DisplayName("MicrometerEngineObserver — meter catalog (design §2.2)")
class MicrometerEngineObserverTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerEngineObserver observer = new MicrometerEngineObserver(registry);

    private static final WorkflowInfo WORKFLOW =
            new WorkflowInfo("wf-1", "OrderWorkflow", "order-service");

    @Test
    @DisplayName("maestro.workflow.started — Counter, tag workflow")
    void workflowStarted() {
        observer.workflowStarted(WORKFLOW);

        assertThat(registry.get("maestro.workflow.started").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("maestro.workflow.completed — Counter, tag workflow")
    void workflowCompleted() {
        observer.workflowCompleted(WORKFLOW);

        assertThat(registry.get("maestro.workflow.completed").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("maestro.workflow.failed — Counter, tag workflow, no exceptionType tag")
    void workflowFailed() {
        observer.workflowFailed(WORKFLOW, "java.lang.RuntimeException");

        assertThat(registry.get("maestro.workflow.failed").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getKey().equals("exceptionType")));
    }

    @Test
    @DisplayName("maestro.workflow.compensated — Counter, tag workflow, fired from workflowCompensating")
    void workflowCompensated() {
        observer.workflowCompensating(WORKFLOW);

        assertThat(registry.get("maestro.workflow.compensated").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("maestro.workflow.terminated — Counter, tag workflow")
    void workflowTerminated() {
        observer.workflowTerminated(WORKFLOW);

        assertThat(registry.get("maestro.workflow.terminated").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("maestro.activity.duration — Timer, tags workflow/activity/outcome=completed, live only")
    void activityDurationCompleted() {
        var activity = new ActivityInfo("wf-1", "OrderWorkflow", "PaymentActivities.charge", 3);

        observer.activityCompleted(activity, Duration.ofMillis(150), false);
        observer.activityCompleted(activity, Duration.ZERO, true); // replayed — must not count

        var timer = registry.get("maestro.activity.duration")
                .tag("workflow", "OrderWorkflow")
                .tag("activity", "PaymentActivities.charge")
                .tag("outcome", "completed")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(150.0);
    }

    @Test
    @DisplayName("maestro.activity.duration — outcome=failed, live only")
    void activityDurationFailed() {
        var activity = new ActivityInfo("wf-1", "OrderWorkflow", "PaymentActivities.charge", 3);

        observer.activityFailed(activity, Duration.ofMillis(75), "java.io.IOException", false);
        observer.activityFailed(activity, Duration.ZERO, "java.io.IOException", true); // replayed

        var timer = registry.get("maestro.activity.duration")
                .tag("workflow", "OrderWorkflow")
                .tag("activity", "PaymentActivities.charge")
                .tag("outcome", "failed")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("maestro.signal.consumed — Counter, tags workflow/signal, live only")
    void signalConsumed() {
        var signal = new SignalInfo("wf-1", "OrderWorkflow", "payment-approved", null);

        observer.signalConsumed(signal, false);
        observer.signalConsumed(signal, true); // replayed — must not count

        assertThat(registry.get("maestro.signal.consumed")
                .tag("workflow", "OrderWorkflow")
                .tag("signal", "payment-approved")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("maestro.timer.fired — Counter, tag workflow, live only")
    void timerFired() {
        var timer = new TimerInfo("wf-1", "OrderWorkflow", "sleep-1");

        observer.timerFired(timer, false);
        observer.timerFired(timer, true); // replayed — must not count

        assertThat(registry.get("maestro.timer.fired").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("maestro.recovery.scanned / maestro.recovery.adopted — untagged Counters")
    void recoveryPass() {
        observer.recoveryPass(5, 2);
        observer.recoveryPass(3, 1);

        assertThat(registry.get("maestro.recovery.scanned").counter().count()).isEqualTo(8.0);
        assertThat(registry.get("maestro.recovery.adopted").counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("maestro.lock.renew.failures — Counter, tag outcome=error|lost")
    void lockRenewFailures() {
        observer.instanceLockRenewFailed("wf-1");
        observer.instanceLockLost("wf-2");
        observer.instanceLockLost("wf-3");

        assertThat(registry.get("maestro.lock.renew.failures").tag("outcome", "error")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("maestro.lock.renew.failures").tag("outcome", "lost")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("maestro.standdown — Counter, tag reason=unknown_event_type|unknown_event_payload|stale_run")
    void standDown() {
        observer.standDown(StandDownReason.UNKNOWN_EVENT_TYPE, "wf-1", "EVT_FROM_A_NEWER_MAESTRO");
        observer.standDown(StandDownReason.UNKNOWN_EVENT_PAYLOAD, "wf-2", null);
        observer.standDown(StandDownReason.STALE_RUN, "wf-3", null);
        observer.standDown(StandDownReason.STALE_RUN, "wf-4", null);

        assertThat(registry.get("maestro.standdown").tag("reason", "unknown_event_type")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("maestro.standdown").tag("reason", "unknown_event_payload")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("maestro.standdown").tag("reason", "stale_run")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a meter name/type conflict is contained locally and never thrown from the callback")
    void meterTypeConflictIsContainedDefensively() {
        // Pre-register a Gauge under the exact name this observer uses as a
        // Counter — Micrometer throws IllegalArgumentException on every
        // .register() call for a name/type mismatch. The callback must
        // neither throw (the RULING 4 composite catches RuntimeException, but
        // an adapter that throws on every emission would still spam the log
        // at the composite layer) nor silently stop working for other meters.
        registry.gauge("maestro.workflow.started", new Object(), o -> 0.0);

        observer.workflowStarted(WORKFLOW);
        observer.workflowStarted(WORKFLOW); // second call must not re-throw or double-log

        // A different, non-conflicting meter must still register normally.
        observer.workflowCompleted(WORKFLOW);
        assertThat(registry.get("maestro.workflow.completed").tag("workflow", "OrderWorkflow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a null workflowType on SignalInfo (pre-delivery edge case) does not throw")
    void signalConsumedToleratesNullWorkflowType() {
        var signal = new SignalInfo("wf-1", null, "payment-approved", null);

        observer.signalConsumed(signal, false);

        assertThat(registry.get("maestro.signal.consumed").tag("signal", "payment-approved")
                .counter().count()).isEqualTo(1.0);
    }
}
