package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5 D-6: {@link GracefulShutdownHandler} as a {@link SmartLifecycle}, with no
 * context — {@code stop(callback)} returns at once and calls back when the
 * engine's shutdown has returned, also when it threw; the handler is not
 * pauseable; it stops in the web server's graceful-shutdown phase.
 */
@DisplayName("GracefulShutdownHandler")
class GracefulShutdownHandlerTest {

    @Test
    void stopWithACallbackReturnsBeforeTheEngineHasShutDown() throws Exception {
        var release = new CountDownLatch(1);
        var engineDone = new CountDownLatch(1);
        var handler = new GracefulShutdownHandler(() -> {
            awaitQuietly(release);
            engineDone.countDown();
        });
        handler.start();
        var callback = new CountDownLatch(1);
        var stopReturned = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            handler.stop(callback::countDown);
            stopReturned.countDown();
        });
        try {
            assertTrue(stopReturned.await(1, SECONDS), "stop(callback) must return while the engine is still draining");
            assertEquals(1, callback.getCount(), "the callback waits for the engine");
        } finally {
            release.countDown();
        }
        assertTrue(callback.await(5, SECONDS));
        assertEquals(0, engineDone.getCount(), "the callback runs after the engine's shutdown returned");
    }

    static Stream<Throwable> failures() {
        // The control-flow Error is logged and rethrown: only a callback in a finally still runs for it.
        return Stream.of(new IllegalStateException("x"), new AssertionError("x"), new ExecutorShutdownException("x"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void theCallbackRunsWhenTheEngineShutdownThrows(Throwable failure) throws Exception {
        var handler = new GracefulShutdownHandler(() -> {
            throw GracefulShutdownHandlerTest.<RuntimeException>sneaky(failure);
        });
        handler.start();
        var callback = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                handler.stop(callback::countDown);
            } catch (Throwable ignored) {
                // today's synchronous stop rethrows before calling back
            }
        });
        assertTrue(callback.await(2, SECONDS), "Spring must get its callback, or the phase waits out its timeout");
    }

    @Test
    void theHandlerIsNotPauseable() {
        assertFalse(new GracefulShutdownHandler(() -> { }).isPauseable());
    }

    @Test
    void stopWithoutACallbackIsSynchronous() {
        var engineDone = new CountDownLatch(1);
        var handler = new GracefulShutdownHandler(engineDone::countDown);
        handler.start();
        handler.stop();
        assertEquals(0, engineDone.getCount(), "stop() returns only after the engine's shutdown returned");
    }

    @Test
    void isRunningIsFalseOnceStopIsRequested() throws Exception {
        var release = new CountDownLatch(1);
        var handler = new GracefulShutdownHandler(() -> awaitQuietly(release));
        handler.start();
        assertTrue(handler.isRunning());
        var callback = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> handler.stop(callback::countDown));
        try {
            org.awaitility.Awaitility.await().atMost(5, SECONDS).until(() -> !handler.isRunning());
        } finally {
            release.countDown();
        }
        assertTrue(callback.await(5, SECONDS));
        assertFalse(handler.isRunning());
    }

    @Test
    void itStopsInTheWebServersGracefulShutdownPhase() {
        assertEquals(SmartLifecycle.DEFAULT_PHASE - 1024, new GracefulShutdownHandler(() -> { }).getPhase());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
