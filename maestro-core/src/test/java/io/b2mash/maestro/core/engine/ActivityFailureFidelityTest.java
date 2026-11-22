package io.b2mash.maestro.core.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.b2mash.maestro.core.annotation.FailureDetails;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ActivityInvocationHandlerTest.InMemoryWorkflowStore;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 42 at the proxy: the live run throws the failure rebuilt from its
 * persisted record — never the activity's own exception object — so it is the
 * failure a replay of the step throws; an opted-in class that cannot be
 * rebuilt faithfully is a {@link ReconstructedActivityFailure} on both paths,
 * reported once, live only.
 */
class ActivityFailureFidelityTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private static final String STEP = "Charging.charge";

    private final InMemoryWorkflowStore store = new InMemoryWorkflowStore();
    private final RecordingEngineObserver observer = new RecordingEngineObserver();

    private Charging proxy(Supplier<RuntimeException> thrown) {
        Charging impl = () -> {
            throw thrown.get();
        };
        return new ActivityProxyFactory().createProxy(Charging.class, impl, store, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), new PayloadSerializer(JsonMapper.builder().build()),
                new RetryExecutor(), WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer,
                TaskQueueGovernor.UNBOUNDED, LifecycleEventSink.NOOP, true);
    }

    /** One call under a fresh context: the first reaches the impl, the next replays its memo. */
    private static ActivityExecutionException call(Charging proxy) {
        var ctx = new WorkflowContext(INSTANCE_ID, "wf-1", UUID.randomUUID(),
                "TestWorkflow", "test-queue", "test-service", 0, true);
        return assertThrows(ActivityExecutionException.class,
                () -> ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(proxy::charge));
    }

    @Test
    void anOptedInFailureIsTheRebuiltObjectLiveAndEqualOnReplay() {
        var thrown = new Declined("declined", "51");
        var proxy = proxy(() -> thrown);

        var live = call(proxy);
        var replayed = call(proxy);

        var liveCause = assertInstanceOf(Declined.class, live.getCause());
        var replayedCause = assertInstanceOf(Declined.class, replayed.getCause());
        assertAll(
                () -> assertNotSame(thrown, liveCause, "never the activity's own object"),
                () -> assertEquals("51", liveCause.getReasonCode()),
                () -> assertEquals("51", replayedCause.getReasonCode()),
                () -> assertEquals("declined", liveCause.getMessage()),
                () -> assertEquals(live.failure(), replayed.failure()),
                () -> assertEquals("51", live.failure().details().get("reasonCode").stringValue()),
                () -> assertEquals(List.of(), observer.unrebuildableFailures()));
    }

    @Test
    void aStringConstructorClassIsRebuiltLiveWithoutItsOtherFields() {
        var thrown = new WithField("boom", "kept only in memory");
        thrown.initCause(new IllegalStateException("root"));
        var proxy = proxy(() -> thrown);

        var live = call(proxy);
        var replayed = call(proxy);

        var liveCause = assertInstanceOf(WithField.class, live.getCause());
        assertAll(
                () -> assertNotSame(thrown, liveCause),
                () -> assertEquals(null, liveCause.extra, "a class that has not opted in records type and message only"),
                () -> assertEquals(null, liveCause.getCause(), "no cause chain"),
                () -> assertEquals("boom", liveCause.getMessage()),
                () -> assertInstanceOf(WithField.class, replayed.getCause()),
                () -> assertTrue(live.failure().details().isEmpty()),
                () -> assertEquals(live.failure(), replayed.failure()));
    }

    @Test
    void anUnfaithfulOptedInClassIsAReconstructionOnBothPathsReportedOnceLiveOnly() {
        var proxy = proxy(() -> new FinalNoCreator("declined", "51"));
        try (var logs = CapturingSlf4jProvider.watch(ActivityInvocationHandler.class)) {
            var live = call(proxy);
            var replayed = call(proxy);
            var again = call(proxy);

            for (var e : List.of(live, replayed, again)) {
                assertEquals(FinalNoCreator.class.getName(),
                        assertInstanceOf(ReconstructedActivityFailure.class, e.getCause()).originalClassName());
                assertEquals("declined", e.getCause().getMessage());
                assertEquals("51", e.failure().details().get("code").stringValue(),
                        "the recorded property stays readable");
                assertTrue(e.causedBy(FinalNoCreator.class));
            }
            assertEquals(1, observer.unrebuildableFailures().size(), "live only");
            var report = observer.unrebuildableFailures().getFirst();
            assertEquals(STEP, report.info().activityName());
            assertEquals(FinalNoCreator.class.getName(), report.exceptionType());
            var names = observer.callbackNames();
            assertTrue(names.indexOf("activityFailureUnrebuildable") < names.indexOf("activityFailed"), names.toString());

            var warns = logs.records().stream()
                    .filter(r -> r.level() == Level.WARN && r.message().contains("@FailureDetails"))
                    .toList();
            assertEquals(1, warns.size(), "WARN once per class, DEBUG after");
            assertTrue(warns.getFirst().message().contains("the rebuilt exception's properties differ"),
                    warns.getFirst().message());
            assertTrue(!warns.getFirst().message().contains("51"), "no property value in the log");
        }
    }

    @Test
    void aLiveTimeoutIsAnActivityTimeoutExceptionOnBothPaths() {
        Charging impl = () -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        };
        var proxy = new ActivityProxyFactory().createProxy(Charging.class, impl, store, null,
                RetryPolicy.noRetry(), Duration.ofMillis(100), new PayloadSerializer(JsonMapper.builder().build()),
                new RetryExecutor(), WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer,
                TaskQueueGovernor.UNBOUNDED, LifecycleEventSink.NOOP, true);

        var live = call(proxy);
        var replayed = call(proxy);

        var liveTimeout = assertInstanceOf(ActivityTimeoutException.class, live.getCause());
        var replayedTimeout = assertInstanceOf(ActivityTimeoutException.class, replayed.getCause());
        assertAll(
                () -> assertEquals(Duration.ofMillis(100), liveTimeout.timeout()),
                () -> assertEquals(Duration.ofMillis(100), replayedTimeout.timeout()),
                () -> assertEquals(STEP, replayedTimeout.activityName()),
                () -> assertEquals(liveTimeout.getMessage(), replayedTimeout.getMessage()),
                () -> assertEquals(1, store.eventsBySequence.values().stream()
                        .filter(e -> e.eventType() == EventType.ACTIVITY_FAILED).count()));
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    interface Charging {
        String charge();
    }

    @FailureDetails
    public static class Declined extends RuntimeException {
        private final String reasonCode;

        @JsonCreator
        public Declined(@JsonProperty("message") String message, @JsonProperty("reasonCode") String reasonCode) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String getReasonCode() {
            return reasonCode;
        }
    }

    public static class WithField extends RuntimeException {
        final String extra;

        public WithField(String m) {
            this(m, null);
        }

        public WithField(String m, String extra) {
            super(m);
            this.extra = extra;
        }
    }

    @FailureDetails
    public static class FinalNoCreator extends RuntimeException {
        private final String code;

        public FinalNoCreator(String m) {
            this(m, null);
        }

        public FinalNoCreator(String m, String code) {
            super(m);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
