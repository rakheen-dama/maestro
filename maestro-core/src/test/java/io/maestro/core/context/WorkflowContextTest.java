package io.maestro.core.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ScopedValue;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowContext}.
 *
 * <p>Tests cover the ScopedValue lifecycle, sequence numbering,
 * replay state management, identity accessors, and thread isolation.
 */
class WorkflowContextTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private WorkflowContext createContext(int initialSequence, boolean replaying) {
        return new WorkflowContext(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "order-abc",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "OrderWorkflow",
                "order-queue",
                "order-service",
                initialSequence,
                replaying
        );
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ScopedValue lifecycle - context is available inside scope, gone outside")
    void scopedValueLifecycle() {
        var ctx = createContext(0, true);

        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            var retrieved = WorkflowContext.current();
            assertEquals(ctx, retrieved, "current() should return the bound context within scope");
        });

        assertThrows(IllegalStateException.class, WorkflowContext::current,
                "current() should throw outside of ScopedValue scope");
    }

    @Test
    @DisplayName("current() without bind throws IllegalStateException")
    void currentWithoutBindThrows() {
        var exception = assertThrows(IllegalStateException.class, WorkflowContext::current);
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("No WorkflowContext bound"),
                "Exception message should indicate no context is bound");
    }

    @Test
    @DisplayName("Sequence numbering - nextSequence() returns 1, 2, 3 incrementally")
    void sequenceNumbering() {
        var ctx = createContext(0, false);

        assertEquals(0, ctx.currentSequence(), "Initial sequence should be 0");

        assertEquals(1, ctx.nextSequence(), "First call to nextSequence() should return 1");
        assertEquals(1, ctx.currentSequence(), "currentSequence() should return last assigned value");

        assertEquals(2, ctx.nextSequence(), "Second call should return 2");
        assertEquals(2, ctx.currentSequence());

        assertEquals(3, ctx.nextSequence(), "Third call should return 3");
        assertEquals(3, ctx.currentSequence());
    }

    @Test
    @DisplayName("Initial sequence - constructed with initialSequence=5, nextSequence() returns 6")
    void initialSequenceOffset() {
        var ctx = createContext(5, false);

        assertEquals(5, ctx.currentSequence(), "currentSequence() should reflect initialSequence");
        assertEquals(6, ctx.nextSequence(), "nextSequence() should return initialSequence + 1");
        assertEquals(7, ctx.nextSequence(), "Second call should return initialSequence + 2");
    }

    @Test
    @DisplayName("Replaying flag - starts as true when constructed with true, can be set to false")
    void replayingFlag() {
        var ctx = createContext(0, true);
        assertTrue(ctx.isReplaying(), "Should be replaying when constructed with true");

        ctx.setReplaying(false);
        assertFalse(ctx.isReplaying(), "Should be false after setReplaying(false)");

        ctx.setReplaying(true);
        assertTrue(ctx.isReplaying(), "Should be true after setReplaying(true)");
    }

    @Test
    @DisplayName("Replaying flag starts as false when constructed with false")
    void replayingFlagStartsFalse() {
        var ctx = createContext(0, false);
        assertFalse(ctx.isReplaying(), "Should not be replaying when constructed with false");
    }

    @Test
    @DisplayName("Identity accessors return constructor values")
    void identityAccessors() {
        var instanceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var runId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        var ctx = new WorkflowContext(
                instanceId,
                "payment-xyz",
                runId,
                "PaymentWorkflow",
                "payment-queue",
                "payment-service",
                0,
                false
        );

        assertEquals(instanceId, ctx.workflowInstanceId());
        assertEquals("payment-xyz", ctx.workflowId());
        assertEquals(runId, ctx.runId());
        assertEquals("PaymentWorkflow", ctx.workflowType());
        assertEquals("payment-queue", ctx.taskQueue());
        assertEquals("payment-service", ctx.serviceName());
    }

    @Test
    @DisplayName("Thread isolation - context bound via ScopedValue is not visible on another thread")
    void threadIsolation() throws Exception {
        var ctx = createContext(0, true);

        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            var otherThreadSawContext = new AtomicBoolean(true);
            var otherThreadException = new AtomicReference<Throwable>();
            var latch = new CountDownLatch(1);

            var thread = Thread.ofVirtual().start(() -> {
                try {
                    WorkflowContext.current();
                    // If we reach here, the context leaked across threads
                    otherThreadSawContext.set(true);
                } catch (IllegalStateException e) {
                    // Expected - ScopedValues do not inherit to child threads
                    otherThreadSawContext.set(false);
                } catch (Throwable t) {
                    otherThreadException.set(t);
                } finally {
                    latch.countDown();
                }
            });

            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS), "Other thread should complete within timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for child thread", e);
            }

            if (otherThreadException.get() != null) {
                throw new AssertionError("Other thread threw unexpected exception", otherThreadException.get());
            }

            assertFalse(otherThreadSawContext.get(),
                    "Context bound via ScopedValue should NOT be visible on another thread");

            // Verify the context is still accessible in this scope
            assertEquals(ctx, WorkflowContext.current(),
                    "Context should still be accessible in the original scope");
        });
    }

    // ── Workflow API delegation tests ───────────────────────────────────

    @Test
    @DisplayName("workflow() is an alias for current()")
    void workflowAliasForCurrent() {
        var ctx = createContext(0, false);

        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            assertEquals(ctx, WorkflowContext.workflow(),
                    "workflow() should return the same context as current()");
        });
    }

    @Test
    @DisplayName("Workflow API methods throw without operations configured")
    void apiMethodsThrowWithoutOperations() {
        var ctx = createContext(0, false);

        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            assertThrows(IllegalStateException.class, () -> ctx.sleep(Duration.ofSeconds(1)),
                    "sleep() should throw when operations not configured");
            assertThrows(IllegalStateException.class, () -> ctx.awaitSignal("foo", String.class, Duration.ofSeconds(1)),
                    "awaitSignal() should throw when operations not configured");
            assertThrows(IllegalStateException.class, ctx::currentTime,
                    "currentTime() should throw when operations not configured");
            assertThrows(IllegalStateException.class, ctx::randomUUID,
                    "randomUUID() should throw when operations not configured");
            assertThrows(IllegalStateException.class, () -> ctx.addCompensation(() -> {}),
                    "addCompensation() should throw when operations not configured");
        });
    }

    @Test
    @DisplayName("Old 8-param constructor still works (backward compat)")
    void oldConstructorBackwardCompat() {
        var ctx = new WorkflowContext(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "test-workflow",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "TestWorkflow",
                "test-queue",
                "test-service",
                0,
                false
        );

        assertEquals("test-workflow", ctx.workflowId());
        assertEquals(1, ctx.nextSequence());
        assertFalse(ctx.isReplaying());
        // API methods should throw since operations is null
        assertThrows(IllegalStateException.class, ctx::currentTime);
    }
}
