package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins C1: when a {@code parallel()} branch fails, the parent counter must
 * still advance past the whole branch space, so saga compensation anchors in
 * its own block instead of coinciding with a sibling branch's block.
 */
@DisplayName("parallel() branch failure and compensation sequence space")
class ParallelFailureCompensationSequenceTest extends ParallelSagaTestSupport {

    @Activity(name = "pay")
    public interface PayActivities {
        @Compensate("releaseReservation")
        String reserve(String item);

        void releaseReservation(String reservation);

        String charge(String item);
    }

    private final AtomicInteger released = new AtomicInteger();
    private PayActivities proxy;

    @BeforeEach
    void setUpProxy() {
        var impl = new PayActivities() {
            @Override public String reserve(String item) { return "reserved:" + item; }
            @Override public void releaseReservation(String reservation) { released.incrementAndGet(); }
            @Override public String charge(String item) { throw new IllegalStateException("declined"); }
        };
        proxy = new ActivityProxyFactory().createProxy(PayActivities.class, impl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(5), serializer, new RetryExecutor());
    }

    @Test
    @DisplayName("a branch failure leaves the parent counter past the branch space, so compensation never reads a sibling branch's slot")
    void failedBranch_compensationRunsInItsOwnBlock() {
        assertThrows(ActivityExecutionException.class, () -> inScope(() ->
                ops.parallel(List.of(() -> proxy.reserve("widget"), () -> proxy.charge("widget")))));

        assertEquals(4000, ctx.currentSequence(), "p=1, 2 branches -> 1*1000 + 3*1000");

        inScope(() -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(1, released.get(), "releaseReservation must actually execute, not replay pay.charge's ACTIVITY_FAILED");
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:parallel",
                "2001:ACTIVITY_COMPLETED:pay.reserve",
                "3001:ACTIVITY_FAILED:pay.charge",
                "4001:COMPENSATION_STARTED:$maestro:compensation",
                "4002000:COMPENSATION_STEP_COMPLETED:pay.releaseReservation",
                "4002001:ACTIVITY_COMPLETED:pay.releaseReservation",
                "4003001:COMPENSATION_COMPLETED:$maestro:compensation"),
                eventLogSortedBySequence());
        assertEquals(0, count(EventType.COMPENSATION_STEP_FAILED));
    }

    @Test
    @DisplayName("a recovering node replays the failed fork, anchors compensation at the same sequence, and re-invokes nothing")
    void failedBranch_replayAnchorsIdentically() {
        failedBranch_compensationRunsInItsOwnBlock();
        var eventsAfterFirstRun = eventLogSortedBySequence();

        ctx = newContext(0, true, ops);
        assertThrows(ActivityExecutionException.class, () -> inScope(() ->
                ops.parallel(List.of(() -> proxy.reserve("widget"), () -> proxy.charge("widget")))));
        assertEquals(4000, ctx.currentSequence());
        inScope(() -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(1, released.get(), "the durable compensation outcome must be replayed, not re-run");
        assertEquals(eventsAfterFirstRun, eventLogSortedBySequence(), "replay appends nothing");
    }
}
