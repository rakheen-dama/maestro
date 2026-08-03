package io.b2mash.maestro.core.observe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TraceContextHolder} — the framework-free hop that
 * carries a raw W3C {@code traceparent} from a transport listener thread into
 * {@code SignalManager.deliverSignal} (design doc §4.3(a)).
 */
@DisplayName("TraceContextHolder carries a raw traceparent across one thread's call stack")
class TraceContextHolderTest {

    @AfterEach
    void clearHolder() {
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("current() is null until something sets it")
    void absentByDefault() {
        assertNull(TraceContextHolder.current());
    }

    @Test
    @DisplayName("set/current/clear round-trip on the calling thread")
    void setCurrentClear() {
        var traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        TraceContextHolder.set(traceparent);
        assertEquals(traceparent, TraceContextHolder.current());

        TraceContextHolder.clear();
        assertNull(TraceContextHolder.current());
    }

    @Test
    @DisplayName("setting null clears — absence is expressible, never an error")
    void setNullClears() {
        TraceContextHolder.set("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        TraceContextHolder.set(null);
        assertNull(TraceContextHolder.current());
    }

    @Test
    @DisplayName("the value is thread-confined — another thread never sees it")
    void threadConfined() throws Exception {
        TraceContextHolder.set("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        var seenElsewhere = new AtomicReference<String>("sentinel");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> seenElsewhere.set(TraceContextHolder.current())).get();
        }

        assertNull(seenElsewhere.get(), "trace context must not leak across threads");
    }

    @Test
    @DisplayName("runWith restores the previous value, even when the action throws")
    void runWithRestores() {
        var outer = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        var inner = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContextHolder.set(outer);

        var seen = new AtomicReference<String>();
        TraceContextHolder.runWith(inner, () -> seen.set(TraceContextHolder.current()));
        assertEquals(inner, seen.get());
        assertEquals(outer, TraceContextHolder.current(), "previous value must be restored");

        try {
            TraceContextHolder.runWith(inner, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the point of the test is the restoration below
        }
        assertEquals(outer, TraceContextHolder.current(),
                "previous value must be restored even when the action throws");
    }
}
