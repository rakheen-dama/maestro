package io.b2mash.maestro.core.observe;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
