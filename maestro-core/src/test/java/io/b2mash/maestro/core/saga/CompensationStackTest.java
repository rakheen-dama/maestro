package io.b2mash.maestro.core.saga;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CompensationStack}.
 */
class CompensationStackTest {

    @Test
    @DisplayName("Push and unwind returns entries in LIFO order")
    void pushAndUnwindReturnsLifoOrder() {
        var stack = new CompensationStack();
        var order = new ArrayList<String>();

        stack.push("step-A", () -> order.add("A"));
        stack.push("step-B", () -> order.add("B"));
        stack.push("step-C", () -> order.add("C"));

        var entries = stack.unwind();
        assertEquals(3, entries.size());

        // LIFO: C, B, A
        assertEquals("step-C", entries.get(0).stepName());
        assertEquals("step-B", entries.get(1).stepName());
        assertEquals("step-A", entries.get(2).stepName());

        // Execute and verify order
        entries.forEach(e -> e.action().run());
        assertEquals(3, order.size());
        assertEquals("C", order.get(0));
        assertEquals("B", order.get(1));
        assertEquals("A", order.get(2));
    }

    @Test
    @DisplayName("Empty stack returns empty list on unwind")
    void emptyStackReturnsEmptyList() {
        var stack = new CompensationStack();
        var entries = stack.unwind();
        assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("push(Runnable) auto-generates step name")
    void pushRunnableAutoGeneratesStepName() {
        var stack = new CompensationStack();
        stack.push(() -> {});
        stack.push(() -> {});

        var entries = stack.unwind();
        assertEquals(2, entries.size());
        // LIFO order — most recent first
        assertTrue(entries.get(0).stepName().startsWith("$compensation:"));
        assertTrue(entries.get(1).stepName().startsWith("$compensation:"));
    }

    @Test
    @DisplayName("push(String, Runnable) uses provided name")
    void pushNamedUsesProvidedName() {
        var stack = new CompensationStack();
        stack.push("my-step", () -> {});

        var entries = stack.unwind();
        assertEquals(1, entries.size());
        assertEquals("my-step", entries.get(0).stepName());
    }

    @Test
    @DisplayName("isEmpty and size track state correctly")
    void isEmptyAndSizeTrackCorrectly() {
        var stack = new CompensationStack();
        assertTrue(stack.isEmpty());
        assertEquals(0, stack.size());

        stack.push("a", () -> {});
        assertFalse(stack.isEmpty());
        assertEquals(1, stack.size());

        stack.push("b", () -> {});
        assertEquals(2, stack.size());

        stack.unwind(); // drains the stack
        assertTrue(stack.isEmpty());
        assertEquals(0, stack.size());
    }

    @Test
    @DisplayName("Concurrent pushes from multiple threads are safe")
    void concurrentPushesAreThreadSafe() throws Exception {
        var stack = new CompensationStack();
        var threadCount = 10;
        var pushesPerThread = 100;
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < pushesPerThread; i++) {
                    stack.push("step-" + counter.getAndIncrement(), () -> {});
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(threadCount * pushesPerThread, stack.size());

        var entries = stack.unwind();
        assertEquals(threadCount * pushesPerThread, entries.size());
        assertTrue(stack.isEmpty());
    }
}
