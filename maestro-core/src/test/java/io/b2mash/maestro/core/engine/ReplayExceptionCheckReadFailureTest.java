package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #83 review: the Issue 89 check reads the store while classifying a
 * failure. When that read itself fails, the failure being classified must not
 * be lost: a branch's error slot is always filled (an empty slot made the join
 * return a {@code null} result, and {@code List.copyOf} threw a
 * {@code NullPointerException} that was recorded as the workflow's failure),
 * a node error from the read ends the run as a node error in a branch does,
 * and any other read failure leaves the branch's own failure in place.
 */
@DisplayName("Issue 89 check — a failing replay lookup never loses the failure it classifies")
class ReplayExceptionCheckReadFailureTest extends ParallelSagaTestSupport {

    private static final class ThrowingLookupStore extends VersionedInMemoryStore {
        volatile @Nullable Throwable lookupFailure;
        /** The lowest sequence whose lookup throws: a branch slot by default, so the fork replays (a one-task parallel() runs inline, unforked). */
        volatile int throwFromSeq = 2000;

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            var failure = sequenceNumber >= throwFromSeq ? lookupFailure : null;
            if (failure instanceof RuntimeException re) throw re;
            if (failure instanceof Error e) throw e;
            return super.getEventBySequence(instanceId, sequenceNumber);
        }
    }

    private ThrowingLookupStore lookup;

    @BeforeEach
    void replayingRunOverAThrowingLookup() {
        lookup = new ThrowingLookupStore();
        store = lookup;
        store.createInstance(instance);
        var parkingLot = new ParkingLot();
        ops = new DefaultWorkflowOperations(store, null, serializer, parkingLot, stack,
                new SignalManager(store, null, null, serializer, parkingLot));
        // The fork is recorded, so the replaying run replays it and its branch
        // contexts are replaying too — the only state in which the check reads.
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1, EventType.SIDE_EFFECT,
                "$maestro:parallel", JsonMapper.builder().build().readTree("{\"branchCount\":2}"), Instant.now()));
        ctx = newContext(0, true, ops);
    }

    @Test
    @DisplayName("a node error from the lookup inside a branch is rethrown raw (NODE_ERROR), the branch's failure kept as suppressed")
    void aNodeErrorFromTheLookupInABranchEndsTheRunAsANodeError() {
        var branchFailure = new IllegalStateException("branch 0 failed");
        var nodeError = new NoClassDefFoundError("com/acme/Missing");
        lookup.lookupFailure = nodeError;
        Callable<String> b0 = () -> {
            throw branchFailure;
        };

        var thrown = assertThrows(Throwable.class, () -> inScope(() -> ops.parallel(List.of(b0, () -> "ok"))));
        assertAll(
                () -> assertSame(nodeError, thrown, "must reach the executor's NODE_ERROR arm, not be "
                        + "recorded as a failure: " + thrown),
                () -> assertEquals(List.of(branchFailure), List.of(thrown.getSuppressed())));
    }

    @Test
    @DisplayName("a store fault from the lookup inside a branch is rethrown (STORE_UNAVAILABLE)")
    void aStoreFaultFromTheLookupInABranchIsRethrown() {
        var storeDown = new StoreUnavailableException("store down", new RuntimeException("db"));
        lookup.lookupFailure = storeDown;
        Callable<String> b0 = () -> {
            throw new IllegalStateException("branch 0 failed");
        };

        assertSame(storeDown, assertThrows(StoreUnavailableException.class,
                () -> inScope(() -> ops.parallel(List.of(b0, () -> "ok")))));
    }

    @Test
    @DisplayName("any other lookup failure inside a branch leaves the branch's own failure in place")
    void anUndecidableLookupKeepsTheBranchsFailure() {
        for (Throwable readFailure : List.of(new IllegalArgumentException("store bug"), new AssertionError("store bug"))) {
            lookup.lookupFailure = readFailure;
            ctx = newContext(0, true, ops);
            var branchFailure = new IllegalStateException("branch 0 failed");
            Callable<String> b0 = () -> {
                throw branchFailure;
            };

            assertSame(branchFailure, assertThrows(IllegalStateException.class,
                    () -> inScope(() -> ops.parallel(List.of(b0, () -> "ok")))), readFailure.toString());
            assertNull(ctx.cancellation().replayException());
        }
    }

    @Test
    @DisplayName("workflow-method path: an undecidable lookup returns null; a node error or store fault is rethrown")
    void theWorkflowMethodPathRethrowsOnlyWhatOutranksAFailure() {
        var failure = new IllegalStateException("workflow failed");
        lookup.throwFromSeq = 1;

        lookup.lookupFailure = new IllegalArgumentException("store bug");
        assertNull(ReplayExceptionCheck.detailOrUndecided(store, ctx, failure));
        lookup.lookupFailure = new AssertionError("store bug");
        assertNull(ReplayExceptionCheck.detailOrUndecided(store, ctx, failure));

        var nodeError = new NoClassDefFoundError("com/acme/Missing");
        lookup.lookupFailure = nodeError;
        assertSame(nodeError, assertThrows(NoClassDefFoundError.class,
                () -> ReplayExceptionCheck.detailOrUndecided(store, ctx, failure)));
        var storeDown = new StoreUnavailableException("store down", new RuntimeException("db"));
        lookup.lookupFailure = storeDown;
        assertSame(storeDown, assertThrows(StoreUnavailableException.class,
                () -> ReplayExceptionCheck.detailOrUndecided(store, ctx, failure)));

        lookup.lookupFailure = null;
        assertTrue(ctx.isReplaying());
        assertEquals("seq=1 stored=$maestro:parallel type=SIDE_EFFECT threw=" + failure,
                ReplayExceptionCheck.detailOrUndecided(store, ctx, failure), "a recorded next slot");
    }
}
