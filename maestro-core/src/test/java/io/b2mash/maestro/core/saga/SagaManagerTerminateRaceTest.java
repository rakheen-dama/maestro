package io.b2mash.maestro.core.saga;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.InstanceStatusWriter;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins Issue 22: an operator terminate landing between
 * {@link SagaManager#compensate}'s terminal-status read and its own
 * {@code COMPENSATING} compare-and-set must not let compensations run.
 *
 * <h2>How the race is modelled</h2>
 * <p>{@link TerminateInterposingStore} wraps a real, CAS-enforcing in-memory
 * store. The very first time {@code updateInstance} is called with
 * {@link WorkflowStatus#COMPENSATING}, it first writes {@code TERMINATED}
 * directly to the delegate — bumping the row's version exactly as
 * {@code WorkflowExecutor.terminateWorkflow} on another node would — and
 * <em>then</em> lets the caller's own {@code COMPENSATING} write proceed
 * against the delegate. That write was built against the pre-terminate
 * version, so it always loses its optimistic-lock check. This reproduces "the
 * guard's read still sees a non-terminal status, but the write that follows
 * loses its CAS to a terminate" deterministically — no latches, no sleeps.
 *
 * @see SagaManager#transitionToCompensating
 */
class SagaManagerTerminateRaceTest {

    // ── The race: a terminate lands between the guard's read and the CAS ──

    @Test
    @DisplayName("A terminate landing between the guard's read and the COMPENSATING CAS "
            + "must not let compensations run")
    void terminateBetweenGuardAndCas_noCompensationsRun() {
        var base = new RaceableStore();
        var store = new TerminateInterposingStore(base);
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var sagaManager = new SagaManager(store, messaging, serializer, "test-service");

        var instance = newInstance(store, "race-workflow");
        var ctx = newCtx(instance);

        var compensationRuns = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", compensationRuns::incrementAndGet);

        assertThrows(WorkflowTerminatedException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false),
                "the run must abandon, not compensate, once it observes the row TERMINATED");

        assertEquals(0, compensationRuns.get(),
                "no compensation may run once a fresh read finds the instance TERMINATED");
        var events = store.getEvents(instance.id());
        assertTrue(events.stream().noneMatch(e -> e.eventType() == EventType.COMPENSATION_STARTED),
                "COMPENSATION_STARTED must not be recorded for a run the terminate won");
        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("race-workflow").orElseThrow().status(),
                "the operator's TERMINATED write must stand, not be overwritten by COMPENSATING");
    }

    // ── Pin: WorkflowTerminatedException actually propagates out of compensate() ──

    @Test
    @DisplayName("compensate() propagates WorkflowTerminatedException — an Error, not swallowed "
            + "by the method's own retry loop")
    void compensate_propagatesWorkflowTerminatedException() {
        var base = new RaceableStore();
        var store = new TerminateInterposingStore(base);
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var sagaManager = new SagaManager(store, messaging, serializer, "test-service");

        var instance = newInstance(store, "propagation-workflow");
        var ctx = newCtx(instance);

        var stack = new CompensationStack();
        stack.push("step-A", () -> { });

        var thrown = assertThrows(WorkflowTerminatedException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false));
        assertEquals("propagation-workflow", thrown.workflowId());
    }

    // ── Pin: exhaustion abandons the run rather than proceeding to compensate ──

    @Test
    @DisplayName("Exhausting the retry budget rethrows the last OptimisticLockException "
            + "after exactly STATUS_WRITE_ATTEMPTS update attempts, and runs zero compensations")
    void exhaustedRetries_rethrowsAndDoesNotCompensate() {
        var base = new RaceableStore();
        var store = new AlwaysConflictingCompensatingStore(base);
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var sagaManager = new SagaManager(store, messaging, serializer, "test-service");

        var instance = newInstance(store, "exhaustion-workflow");
        var ctx = newCtx(instance);

        var compensationRuns = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", compensationRuns::incrementAndGet);

        assertThrows(OptimisticLockException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false),
                "exhaustion must abandon the run by rethrowing, not fall through to compensate");

        assertEquals(InstanceStatusWriter.STATUS_WRITE_ATTEMPTS, store.compensatingUpdateAttempts(),
                "the loop must retry exactly the shared budget, no more, no fewer");
        assertEquals(0, compensationRuns.get(), "nothing may run once the write budget is exhausted");
        assertTrue(store.getEvents(instance.id()).isEmpty(),
                "no COMPENSATION_STARTED may be recorded when the transition never succeeded");
        assertFalse(store.getInstance("exhaustion-workflow").orElseThrow().status().isTerminal(),
                "the instance must stay active so recovery can retry the transition");
    }

    // ── Pin (CR-11 / B1a): a generic store failure must not let compensations run ──

    @Test
    @DisplayName("A non-OptimisticLockException store failure on the COMPENSATING write "
            + "must not let compensations run")
    void storeFailureOnCompensatingWrite_noCompensationsRun() {
        var base = new RaceableStore();
        var store = new FailingCompensatingWriteStore(base);
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var sagaManager = new SagaManager(store, messaging, serializer, "test-service");

        var instance = newInstance(store, "store-failure-workflow");
        var ctx = newCtx(instance);

        var compensationRuns = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", compensationRuns::incrementAndGet);

        assertThrows(RuntimeException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false),
                "a generic store failure must abandon the run, not fall through to compensate "
                        + "against an unconfirmed COMPENSATING write");

        assertEquals(0, compensationRuns.get(),
                "no compensation may run when the COMPENSATING write itself failed unconfirmed");
        assertTrue(store.getEvents(instance.id()).isEmpty(),
                "no COMPENSATION_STARTED may be recorded when the transition never succeeded");
    }

    // ── Pin (CR-11 sibling / B1b): a workflow another runner already finalised
    //    must not be compensated by a stale run ──

    @Test
    @DisplayName("A workflow another runner already finalised (COMPLETED) "
            + "must not be compensated by a stale run")
    void alreadyFinalizedByAnotherRunner_noCompensationsRun() {
        var base = new RaceableStore();
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var sagaManager = new SagaManager(base, messaging, serializer, "test-service");

        var instance = newInstance(base, "finalized-workflow");
        // Another runner already finished this workflow successfully before
        // this (stale) run gets to its own COMPENSATING transition.
        base.updateInstance(instance.toBuilder()
                .status(WorkflowStatus.COMPLETED)
                .completedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(instance.version() + 1)
                .build());
        var ctx = newCtx(instance);

        var compensationRuns = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", compensationRuns::incrementAndGet);

        assertThrows(StaleRunException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false),
                "a stale run must not compensate a workflow another runner already COMPLETED");

        assertEquals(0, compensationRuns.get(),
                "no compensation may run against an instance another runner already finalised");
        assertTrue(base.getEvents(instance.id()).isEmpty(),
                "no COMPENSATION_STARTED may be recorded for a stale run over a finalised instance");
        assertEquals(WorkflowStatus.COMPLETED, base.getInstance("finalized-workflow").orElseThrow().status(),
                "the winning runner's COMPLETED write must stand, not be overwritten by a stale run");
    }

    // ── Pin: recovery re-entry against an already-COMPENSATING row still compensates ──

    @Test
    @DisplayName("Recovery re-entry: an instance already COMPENSATING from a prior crash "
            + "still gets compensated")
    void recoveryReentry_alreadyCompensating_stillCompensates() {
        var base = new RaceableStore();
        var messaging = new RecordingMessaging();
        var serializer = new PayloadSerializer(new ObjectMapper());
        var sagaManager = new SagaManager(base, messaging, serializer, "test-service");

        var instance = newInstance(base, "recovering-workflow");
        // A prior attempt crashed after writing COMPENSATING but before
        // finishing the unwind — exactly what a recovering node reads.
        base.updateInstance(instance.toBuilder()
                .status(WorkflowStatus.COMPENSATING)
                .updatedAt(Instant.now())
                .version(instance.version() + 1)
                .build());
        var ctx = newCtx(instance);

        var compensationRuns = new AtomicInteger();
        var stack = new CompensationStack();
        stack.push("step-A", compensationRuns::incrementAndGet);

        sagaManager.compensate(ctx, instance, stack, false);

        assertEquals(1, compensationRuns.get(),
                "a recovering run must still compensate when the instance already reads COMPENSATING");
        assertEquals(WorkflowStatus.COMPENSATING,
                base.getInstance("recovering-workflow").orElseThrow().status());
    }

    // ── C7: the per-entry guard ──────────────────────────────────────────

    @Test
    @DisplayName("a terminate landing while entry 0 runs stops the loop before entry 1")
    void terminateDuringEntryZero_stopsBeforeEntryOne() {
        var store = new RaceableStore();
        var sagaManager = new SagaManager(store, new RecordingMessaging(),
                new PayloadSerializer(new ObjectMapper()), "test-service");
        var instance = newInstance(store, "per-entry");
        var ctx = newCtx(instance);
        var runs = new CopyOnWriteArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-C", () -> runs.add("C"));
        stack.push("step-B", () -> runs.add("B"));
        stack.push("step-A", () -> { // LIFO: A runs first
            runs.add("A");
            forceStatus(store, "per-entry", WorkflowStatus.TERMINATED);
        });

        assertThrows(WorkflowTerminatedException.class,
                () -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(List.of("A"), runs, "entries 1..n must not run once the instance reads TERMINATED");
        var events = store.getEvents(instance.id());
        assertEquals(1, events.stream()
                        .filter(e -> e.eventType() == EventType.COMPENSATION_STEP_COMPLETED).count(),
                "entry 0 ran to completion and its outcome stays durable");
        assertTrue(events.stream().noneMatch(e -> e.eventType() == EventType.COMPENSATION_COMPLETED));
        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("per-entry").orElseThrow().status());
    }

    @Test
    @DisplayName("another runner finalising the workflow mid-compensation stands this run down as stale")
    void otherRunnerFinalisedDuringEntryZero_standsDownAsStale() {
        var store = new RaceableStore();
        var sagaManager = new SagaManager(store, new RecordingMessaging(),
                new PayloadSerializer(new ObjectMapper()), "test-service");
        var instance = newInstance(store, "per-entry-stale");
        var ctx = newCtx(instance);
        var runs = new CopyOnWriteArrayList<String>();
        var stack = new CompensationStack();
        stack.push("step-C", () -> runs.add("C"));
        stack.push("step-B", () -> runs.add("B"));
        stack.push("step-A", () -> {
            runs.add("A");
            forceStatus(store, "per-entry-stale", WorkflowStatus.FAILED);
        });

        assertThrows(StaleRunException.class, () -> sagaManager.compensate(ctx, instance, stack, false));

        assertEquals(List.of("A"), runs);
        assertEquals(io.b2mash.maestro.core.context.RunCancellation.Cause.STALE, ctx.cancellation().cause(),
                "the fresh read's stand-down is sticky on the run (round 2, R33)");
        assertTrue(store.getEvents(instance.id()).stream()
                .noneMatch(e -> e.eventType() == EventType.COMPENSATION_COMPLETED));
    }

    @Test
    @DisplayName("exactly one instance read per live entry; replay-skipped entries read nothing")
    void perEntryGuard_costsOneReadPerLiveEntry() {
        var counting = new CountingStore(new RaceableStore());
        var sagaManager = new SagaManager(counting, new RecordingMessaging(),
                new PayloadSerializer(new ObjectMapper()), "test-service");
        var instance = newInstance(counting, "per-entry-reads");
        var ctx = newCtx(instance);
        var stack = new CompensationStack();
        stack.push("step-C", () -> { });
        stack.push("step-B", () -> { });
        stack.push("step-A", () -> { });

        counting.instanceReads.set(0);
        sagaManager.compensate(ctx, instance, stack, false);
        assertEquals(1 + 3, counting.instanceReads.get(),
                "1 (transitionToCompensating) + one per live entry");

        // Recovery re-run: every entry is now durable, so each is replay-skipped.
        var replayCtx = newCtx(instance);
        replayCtx.setReplaying(true);
        var replayStack = new CompensationStack(); // compensate() drained the first one
        replayStack.push("step-C", () -> { });
        replayStack.push("step-B", () -> { });
        replayStack.push("step-A", () -> { });
        counting.instanceReads.set(0);
        sagaManager.compensate(replayCtx, instance, replayStack, false);
        assertEquals(1, counting.instanceReads.get(),
                "replay-skipped entries run nothing, so they read nothing");
    }

    private static void forceStatus(RaceableStore store, String workflowId, WorkflowStatus status) {
        var latest = store.getInstance(workflowId).orElseThrow();
        store.updateInstance(latest.toBuilder().status(status).version(latest.version() + 1).build());
    }

    /** Counts {@code getInstance} calls; everything else passes through. */
    private static final class CountingStore extends DelegatingStore {
        final AtomicInteger instanceReads = new AtomicInteger();

        CountingStore(WorkflowStore delegate) {
            super(delegate);
        }

        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            instanceReads.incrementAndGet();
            return delegate.getInstance(workflowId);
        }

        @Override public void updateInstance(WorkflowInstance instance) { delegate.updateInstance(instance); }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private WorkflowInstance newInstance(WorkflowStore store, String workflowId) {
        var instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("TestWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(5)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(0)
                .build();
        store.createInstance(instance);
        return instance;
    }

    private WorkflowContext newCtx(WorkflowInstance instance) {
        return new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), instance.serviceName(),
                5, false);
    }

    /**
     * Shared base for the fixture stores below (CR-1): every fixture wraps a
     * real delegate store and overrides only {@code updateInstance} to inject
     * its scenario — every other {@link WorkflowStore} method is a plain
     * pass-through. Extracted so adding a new SPI method only requires
     * touching this one class, not every fixture individually.
     */
    private abstract static class DelegatingStore implements WorkflowStore {
        protected final WorkflowStore delegate;

        DelegatingStore(WorkflowStore delegate) {
            this.delegate = delegate;
        }

        @Override public WorkflowInstance createInstance(WorkflowInstance instance) {
            return delegate.createInstance(instance);
        }
        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            return delegate.getInstance(workflowId);
        }
        @Override public List<WorkflowInstance> getRecoverableInstances() {
            return delegate.getRecoverableInstances();
        }
        @Override public void appendEvent(WorkflowEvent event) { delegate.appendEvent(event); }
        @Override public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            return delegate.completeInstance(terminal, terminalEvent);
        }
        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq) {
            return delegate.getEventBySequence(instanceId, seq);
        }
        @Override public List<WorkflowEvent> getEvents(UUID instanceId) { return delegate.getEvents(instanceId); }
        @Override public int deleteFailureEvents(UUID instanceId) { return delegate.deleteFailureEvents(instanceId); }
        @Override public void saveSignal(WorkflowSignal signal) { delegate.saveSignal(signal); }
        @Override public List<WorkflowSignal> getUnconsumedSignals(String wfId, String name) {
            return delegate.getUnconsumedSignals(wfId, name);
        }
        @Override public boolean markSignalConsumed(UUID signalId) { return delegate.markSignalConsumed(signalId); }
        @Override public void adoptOrphanedSignals(String wfId, UUID instanceId) {
            delegate.adoptOrphanedSignals(wfId, instanceId);
        }
        @Override public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            return delegate.deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff);
        }
        @Override public void saveTimer(WorkflowTimer timer) { delegate.saveTimer(timer); }
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return delegate.getDueTimers(now, batchSize);
        }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return delegate.findTimer(workflowInstanceId, timerId);
        }
        @Override public boolean markTimerFired(UUID timerId) { return delegate.markTimerFired(timerId); }
        @Override public boolean markTimerCancelled(UUID timerId) { return delegate.markTimerCancelled(timerId); }
    }

    /**
     * Injects a cross-node {@code TERMINATED} write just before the first
     * {@code COMPENSATING} CAS the caller attempts — modelling
     * {@code WorkflowExecutor.terminateWorkflow} on another node landing in
     * the gap between {@code transitionToCompensating}'s guard read and its
     * own write.
     */
    private static final class TerminateInterposingStore extends DelegatingStore {
        private final AtomicBoolean injected = new AtomicBoolean();

        TerminateInterposingStore(WorkflowStore delegate) {
            super(delegate);
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            if (instance.status() == WorkflowStatus.COMPENSATING && injected.compareAndSet(false, true)) {
                // What WorkflowExecutor.terminateWorkflow on another node does,
                // landing between the caller's read and its own CAS below.
                var current = delegate.getInstance(instance.workflowId()).orElseThrow();
                delegate.updateInstance(current.toBuilder()
                        .status(WorkflowStatus.TERMINATED)
                        .completedAt(Instant.now())
                        .updatedAt(Instant.now())
                        .version(current.version() + 1)
                        .build());
                // The caller's CAS below was built against the pre-terminate
                // version, so it now loses against the delegate.
            }
            delegate.updateInstance(instance);
        }
    }

    /**
     * Delegating store whose {@code updateInstance} always throws
     * {@link OptimisticLockException} for a {@code COMPENSATING} write, while
     * {@code getInstance} keeps returning the untouched, still-active instance
     * — modelling an instance row under continuous contention from a writer
     * whose intent this run never gets to observe.
     */
    private static final class AlwaysConflictingCompensatingStore extends DelegatingStore {
        private final AtomicInteger compensatingUpdateAttempts = new AtomicInteger();

        AlwaysConflictingCompensatingStore(WorkflowStore delegate) {
            super(delegate);
        }

        int compensatingUpdateAttempts() {
            return compensatingUpdateAttempts.get();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            if (instance.status() == WorkflowStatus.COMPENSATING) {
                compensatingUpdateAttempts.incrementAndGet();
                throw new OptimisticLockException(instance.workflowId(),
                        instance.version() - 1, instance.version() - 1);
            }
            delegate.updateInstance(instance);
        }
    }

    /**
     * Delegating store whose {@code updateInstance} throws a generic
     * (non-{@link OptimisticLockException}) failure for a {@code COMPENSATING}
     * write — modelling a store outage (connection loss, timeout) rather than
     * a lost compare-and-set. Pins CR-11: this must abandon the run exactly
     * like a lost CAS does, not fall through to compensate.
     */
    private static final class FailingCompensatingWriteStore extends DelegatingStore {
        FailingCompensatingWriteStore(WorkflowStore delegate) {
            super(delegate);
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            if (instance.status() == WorkflowStatus.COMPENSATING) {
                throw new RuntimeException("simulated store outage");
            }
            delegate.updateInstance(instance);
        }
    }

    /**
     * A real, CAS-enforcing in-memory store — {@code updateInstance} throws
     * {@link OptimisticLockException} unless the caller's version is exactly
     * one past the stored version, matching the {@link WorkflowStore} SPI
     * contract. Mirrors {@code WorkflowExecutorTerminalTransitionTest}'s
     * {@code VersionedStore}.
     */
    private static final class RaceableStore implements WorkflowStore {
        private final ConcurrentHashMap<String, WorkflowInstance> instancesByWorkflowId = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<WorkflowEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var prev = instancesByWorkflowId.putIfAbsent(instance.workflowId(), instance);
            if (prev != null) throw new WorkflowAlreadyExistsException(instance.workflowId());
            return instance;
        }

        @Override public Optional<WorkflowInstance> getInstance(String workflowId) {
            return Optional.ofNullable(instancesByWorkflowId.get(workflowId));
        }

        @Override public List<WorkflowInstance> getRecoverableInstances() {
            return instancesByWorkflowId.values().stream().filter(i -> i.status().isActive()).toList();
        }

        @Override
        public synchronized void updateInstance(WorkflowInstance instance) {
            var stored = instancesByWorkflowId.get(instance.workflowId());
            if (stored == null) {
                throw new IllegalStateException("cannot update an instance that was never created: "
                        + instance.workflowId());
            }
            if (instance.version() != stored.version() + 1) {
                throw new OptimisticLockException(instance.workflowId(), instance.version() - 1, stored.version());
            }
            instancesByWorkflowId.put(instance.workflowId(), instance);
        }

        @Override public void appendEvent(WorkflowEvent event) { events.add(event); }

        @Override
        public synchronized boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            // Test fixture: composes this store's own CAS and append under its monitor
            // (so every hook on either still fires), undoing the status write if the
            // append fails and the row is still ours — nothing is written unless both land.
            var before = instancesByWorkflowId.get(terminal.workflowId());
            try {
                updateInstance(terminal);
            } catch (OptimisticLockException conflict) {
                return false;
            }
            try {
                appendEvent(terminalEvent);
            } catch (RuntimeException appendFailed) {
                // Restore only if the row is still ours — never clobber a later writer.
                instancesByWorkflowId.replace(terminal.workflowId(), terminal, before);
                throw appendFailed;
            }
            return true;
        }

        @Override public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq) {
            return events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId) && e.sequenceNumber() == seq)
                    .findFirst();
        }

        @Override public List<WorkflowEvent> getEvents(UUID instanceId) {
            return events.stream().filter(e -> e.workflowInstanceId().equals(instanceId)).toList();
        }

        @Override public int deleteFailureEvents(UUID instanceId) {
            var toRemove = events.stream()
                    .filter(e -> e.workflowInstanceId().equals(instanceId)
                            && (e.eventType() == EventType.ACTIVITY_FAILED
                                    || e.eventType() == EventType.WORKFLOW_FAILED))
                    .toList();
            events.removeAll(toRemove);
            return toRemove.size();
        }

        @Override public void saveSignal(WorkflowSignal signal) {}
        @Override public List<WorkflowSignal> getUnconsumedSignals(String wfId, String name) { return List.of(); }
        @Override public boolean markSignalConsumed(UUID signalId) { return true; }
        @Override public void adoptOrphanedSignals(String wfId, UUID instanceId) {}
        @Override public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) { return 0; }
        @Override public void saveTimer(WorkflowTimer timer) {}
        @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) { return List.of(); }
        @Override public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return Optional.empty();
        }
        @Override public boolean markTimerFired(UUID timerId) { return false; }
        @Override public boolean markTimerCancelled(UUID timerId) { return false; }
    }

    private static class RecordingMessaging implements WorkflowMessaging {
        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override public void publishSignal(String serviceName, SignalMessage message) {}
        @Override public void publishLifecycleEvent(WorkflowLifecycleEvent event) { events.add(event); }
        @Override public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {}
    }
}
