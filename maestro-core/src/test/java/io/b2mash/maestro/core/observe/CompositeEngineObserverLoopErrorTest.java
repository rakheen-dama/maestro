package io.b2mash.maestro.core.observe;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompositeEngineObserver#backgroundLoopError} isolates every delegate
 * from the others for any {@code Throwable} but a
 * {@code MaestroControlFlowError}: an {@code Error} from one delegate must not
 * skip the delegates after it (a metrics adapter after a broken one would
 * otherwise never count the loop error).
 */
class CompositeEngineObserverLoopErrorTest {

    private static EngineObserver throwing(Throwable t) {
        return new EngineObserver() {
            @Override
            public void backgroundLoopError(String loop, String errorType) {
                throw sneaky(t);
            }
        };
    }

    @Test
    void anErrorFromOneDelegateDoesNotSkipTheNext() {
        var recorder = new RecordingEngineObserver();
        var composite = CompositeEngineObserver.of(List.of(throwing(new AssertionError("observer bug")), recorder));

        assertDoesNotThrow(() -> composite.backgroundLoopError("timer-poller", "java.lang.OutOfMemoryError"));

        assertEquals(List.of(new RecordingEngineObserver.LoopErrorCall("timer-poller", "java.lang.OutOfMemoryError")),
                recorder.loopErrors());
    }

    @Test
    void aControlFlowErrorFromADelegateStillPropagates() {
        var composite = CompositeEngineObserver.of(List.of(
                throwing(new ExecutorShutdownException("test")), new RecordingEngineObserver()));

        assertThrows(ExecutorShutdownException.class,
                () -> composite.backgroundLoopError("timer-poller", "java.lang.AssertionError"));
    }

    /**
     * Review d6: a delegate that throws on every call is logged at WARN with
     * the stack trace once per loop while it keeps failing — repeats at DEBUG —
     * and again once it has succeeded in between.
     */
    @Test
    void aDelegateThatKeepsThrowingIsWarnedOncePerStreak() {
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var flaky = new EngineObserver() {
            @Override
            public void backgroundLoopError(String loop, String errorType) {
                if (failing.get()) {
                    throw new AssertionError("observer bug");
                }
            }
        };
        var composite = CompositeEngineObserver.of(List.of(flaky));
        try (var logs = CapturingSlf4jProvider.watch(CompositeEngineObserver.class)) {
            composite.backgroundLoopError("timer-poller", "java.lang.OutOfMemoryError");
            composite.backgroundLoopError("timer-poller", "java.lang.OutOfMemoryError");
            composite.backgroundLoopError("timer-poller", "java.lang.OutOfMemoryError");
            composite.backgroundLoopError("recovery-poller", "java.lang.OutOfMemoryError");   // another loop: its own streak
            failing.set(false);
            composite.backgroundLoopError("timer-poller", "java.lang.OutOfMemoryError");      // ends the streak
            failing.set(true);
            composite.backgroundLoopError("timer-poller", "java.lang.OutOfMemoryError");      // a new streak

            var warns = logs.records().stream().filter(r -> r.level() == Level.WARN).toList();
            assertEquals(3, warns.size(), "one WARN per delegate per loop per streak: " + logs.records());
            assertTrue(warns.stream().allMatch(r -> r.throwable() instanceof AssertionError),
                    "each WARN carries the stack trace");
            assertEquals(2, logs.records().stream().filter(r -> r.level() == Level.DEBUG).count(),
                    "the repeats are DEBUG: " + logs.records());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
