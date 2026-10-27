package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ActivityInvocationHandlerTest.InMemoryWorkflowStore;
import io.b2mash.maestro.core.engine.ActivityInvocationHandlerTest.RecordingLock;
import io.b2mash.maestro.core.engine.ActivityInvocationHandlerTest.RecordingMessaging;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.ScopedValue;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C15: a memoized activity failure replays deterministically — the original
 * type only through a public {@code (String)} constructor, otherwise a
 * {@link ReconstructedActivityFailure} carrying the original class name; never
 * a bare {@code RuntimeException} and never an engine control-flow signal.
 */
class ActivityFailureReconstructionTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STEP = "ExplodingActivities.explode";

    private InMemoryWorkflowStore store;
    private ExplodingActivities proxy;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        ExplodingActivities impl = () -> {
            throw new NoStringCtorException("boom", 7);
        };
        proxy = new ActivityProxyFactory().createProxy(
                ExplodingActivities.class, impl,
                store, new RecordingLock(), new RecordingMessaging(),
                RetryPolicy.noRetry(), Duration.ofSeconds(30),
                new PayloadSerializer(MAPPER), new RetryExecutor());
    }

    /** Runs the proxy call under a fresh context (sequence restarts at 0). */
    private void callExplode() {
        var ctx = new WorkflowContext(INSTANCE_ID, "wf-1", UUID.randomUUID(),
                "TestWorkflow", "test-queue", "test-service", 0, true);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> proxy.explode());
    }

    private ActivityExecutionException replayFailureWithPayload(Map<String, String> payload) {
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), INSTANCE_ID, 1,
                EventType.ACTIVITY_FAILED, STEP, MAPPER.valueToTree(payload), Instant.now()));
        return assertThrows(ActivityExecutionException.class, this::callExplode);
    }

    private ActivityExecutionException replayFailure(String exceptionType, String message) {
        return replayFailureWithPayload(Map.of("exceptionType", exceptionType, "message", message));
    }

    @Test
    void publicStringCtor_replaysTheOriginalType() {
        var ex = replayFailure(DomainException.class.getName(), "boom");
        assertAll(() -> assertInstanceOf(DomainException.class, ex.getCause()),
                () -> assertEquals("boom", ex.getCause().getMessage()));
    }

    @Test
    void noStringCtor_replaysAsReconstructedActivityFailure() {
        var ex = replayFailure(NoStringCtorException.class.getName(), "boom");
        var cause = assertInstanceOf(ReconstructedActivityFailure.class, ex.getCause());
        assertAll(() -> assertEquals(NoStringCtorException.class.getName(), cause.originalClassName()),
                () -> assertEquals("boom", cause.getMessage()));
    }

    @Test
    void privateStringCtor_replaysAsReconstructed() {
        assertInstanceOf(ReconstructedActivityFailure.class,
                replayFailure(PrivateCtorException.class.getName(), "boom").getCause());
    }

    @Test
    void unknownClass_replaysAsReconstructed() {
        var cause = assertInstanceOf(ReconstructedActivityFailure.class,
                replayFailure("com.example.Gone", "boom").getCause());
        assertEquals("com.example.Gone", cause.originalClassName());
    }

    @Test
    void missingExceptionType_replaysAsReconstructedUnknown() {
        var cause = assertInstanceOf(ReconstructedActivityFailure.class,
                replayFailureWithPayload(Map.of("message", "boom")).getCause());
        assertEquals("unknown", cause.originalClassName());
    }

    @Test
    void controlFlowErrorName_isNeverInstantiated() {
        var cause = replayFailure(ExecutorShutdownException.class.getName(), "deploying").getCause();
        assertInstanceOf(ReconstructedActivityFailure.class, cause);
        assertFalse(cause instanceof MaestroControlFlowError);
    }

    @Test
    void liveAndReplay_agreeOnTheWrapperAndOnOriginalClassName() {
        var live = assertThrows(ActivityExecutionException.class, this::callExplode);
        // A fresh context restarts the sequence, so this reads the ACTIVITY_FAILED just persisted.
        var replayed = assertThrows(ActivityExecutionException.class, this::callExplode);
        assertAll(
                () -> assertEquals(live.activityName(), replayed.activityName()),
                () -> assertInstanceOf(NoStringCtorException.class, live.getCause()),
                () -> assertEquals(NoStringCtorException.class.getName(),
                        assertInstanceOf(ReconstructedActivityFailure.class, replayed.getCause()).originalClassName()),
                () -> assertEquals("boom", replayed.getCause().getMessage()));
    }

    interface ExplodingActivities {
        String explode();
    }

    public static class DomainException extends RuntimeException {
        public DomainException(String m) {
            super(m);
        }
    }

    public static class NoStringCtorException extends RuntimeException {
        public NoStringCtorException(String m, int code) {
            super(m);
        }
    }

    public static class PrivateCtorException extends RuntimeException {
        private PrivateCtorException(String m) {
            super(m);
        }
    }
}
