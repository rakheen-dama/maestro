package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * WP6 / Issue 30 (3.1): a recovery-poller pass reserves a task-queue slot
 * before it locks an instance, so a node locks only what it can run or queue
 * (C2: its free slots plus one queue's concurrency of queued runs) and a peer
 * adopts the rest.
 */
class RecoveryCapacityTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final CountingStore store = new CountingStore();
    private final SharedLock lock = new SharedLock();
    private final BlockingWork work = new BlockingWork();
    private final List<WorkflowExecutor> nodes = new ArrayList<>();
    private final Map<WorkflowExecutor, String> names = new java.util.IdentityHashMap<>();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        nodes.forEach(WorkflowExecutor::shutdown);
    }

    @Test
    void aNodeLocksOnlyWhatItCanRunOrQueueAndThePeerAdoptsTheRest() throws Exception {
        for (var i = 0; i < 6; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(2);
        var b = node(2);
        var passA = a.recoveryPass(registrations(a));
        var locksAfterA = lock.heldCount();
        var passB = b.recoveryPass(registrations(b));
        assertAll(
                () -> assertEquals(4, locksAfterA, "A holds a lock only for what it runs or queues"),
                () -> assertEquals(4, passA.adopted(), "A adopts its 2 slots plus 2 queued (C2)"),
                () -> assertEquals(2, passA.deferred()),
                () -> assertEquals(2, passB.adopted(), "B adopts what A left"),
                () -> assertEquals(2, a.recoveryDeferredCount()));
        work.release(6);
        assertEquals(Set.of("A", "B"), valueWithin(work::nodesThatRan, Set.of("A", "B"), Duration.ofSeconds(10)));
    }

    @Test
    void anUnboundedNodeAdoptsEveryInstanceAsBefore() {
        for (var i = 0; i < 6; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(0);
        var pass = a.recoveryPass(registrations(a));
        assertAll(
                () -> assertEquals(6, pass.adopted()),
                () -> assertEquals(0, pass.deferred()),
                () -> assertEquals(6, lock.heldCount()));
    }

    /** MD-1: the public pass stays ungated, as {@code WorkflowExecutorConcurrencyTest.recoveryOfMoreRuns…} pins. */
    @Test
    void recoverWorkflowsStaysUngated() {
        for (var i = 0; i < 5; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(1);
        assertAll(
                () -> assertEquals(5, a.recoverWorkflows(registrations(a))),
                () -> assertEquals(5, lock.heldCount()));
    }

    /**
     * D-1, W1–W8: every way an admitted instance can fail to become a run
     * gives its reservation back, once, and leaves no lock behind where one
     * was taken. One slot and one ticket per node, so a leaked permit or
     * ticket shows as a missing slot or a headroom of 1.
     *
     * <p>W1 and W2 cannot be reached through a pass: a shutting-down executor
     * has closed its governor (admission defers everything) and a pass drops
     * a run already running here before admission. They call the
     * reservation-taking {@code resumeWorkflow} overload directly, which is
     * where the release lives.
     */
    @ParameterizedTest
    @EnumSource(Window.class)
    void aDeferredOrFailedAdoptionGivesItsReservationBack(Window window) throws Exception {
        var a = window.drive(this);
        assertAll(
                () -> assertEquals(OptionalInt.of(window.slots),
                        a.taskQueueGovernor().availableWorkflowSlots("orders")),
                () -> assertEquals(window.headroom, a.taskQueueGovernor().recoveryHeadroom("orders"),
                        "slot + ticket whole"),
                () -> assertEquals(window.lockHeld, lock.isHeld("maestro:lock:workflow:wf-1"),
                        "no lock left behind"));
    }

    /** The windows of D-1's table, each with what the queue must read afterwards. */
    enum Window {
        /** W1: the reservation was taken, then the executor began shutting down (governor closed: headroom 0). */
        SHUTTING_DOWN(1, 0, false) {
            @Override
            WorkflowExecutor drive(RecoveryCapacityTest t) {
                var a = t.node(1);
                var instance = seedRecoverable(t.store, "wf-1", "orders");
                var reservation = a.taskQueueGovernor().admitRecovery("orders");
                a.shutdown();
                assertFalse(a.resumeWorkflow(instance, () -> fail("not built"), workMethod(), reservation));
                return a;
            }
        },
        /** W2: a run of the same id already runs here and holds the only slot; the QUEUED ticket comes back. */
        RUNNING_HERE(0, 1, true) {
            @Override
            WorkflowExecutor drive(RecoveryCapacityTest t) {
                var a = t.node(1);
                var reg = t.registrations(a).get("WorkWorkflow");
                a.startWorkflow("wf-1", "WorkWorkflow", "orders", null, reg.workflowFactory(), workMethod());
                assertEquals(1, valueWithin(t.work::entries, 1, Duration.ofSeconds(5)));
                var reservation = a.taskQueueGovernor().admitRecovery("orders");
                assertEquals(TaskQueueGovernor.SlotReservation.Kind.QUEUED, reservation.kind());
                var instance = t.store.getInstance("wf-1").orElseThrow();
                assertFalse(a.resumeWorkflow(instance, reg.workflowFactory(), workMethod(), reservation));
                return a;
            }
        },
        /** W3: heldKeys reports nothing, but tryAcquire is refused. */
        LOCK_HELD_ELSEWHERE {
            @Override
            void arm(RecoveryCapacityTest t, WorkflowExecutor a) {
                t.lock.refuse.add("maestro:lock:workflow:wf-1");
            }
        },
        /** W4: the instanceLockAcquired callback throws after the handle was recorded. */
        LOCK_CALLBACK_ERROR {
            @Override
            WorkflowExecutor node(RecoveryCapacityTest t) {
                var thrown = new AtomicBoolean();
                return t.node(1, new EngineObserver() {
                    @Override
                    public void instanceLockAcquired(String workflowId) {
                        if (thrown.compareAndSet(false, true)) {
                            throw new AssertionError("injected in instanceLockAcquired");
                        }
                    }
                });
            }
        },
        /** W5: the re-read finds the instance terminal. */
        TURNED_TERMINAL {
            @Override
            void arm(RecoveryCapacityTest t, WorkflowExecutor a) {
                t.store.getInstanceHooks.put("wf-1",
                        row -> row.map(i -> i.toBuilder().status(WorkflowStatus.COMPLETED).build()));
            }
        },
        /** W6: the adoption compare-and-set is lost. */
        CAS_LOST {
            @Override
            void arm(RecoveryCapacityTest t, WorkflowExecutor a) {
                t.store.failNextUpdates(1);
            }
        },
        /** W7: the re-read throws a store fault. */
        STORE_FAULT {
            @Override
            void arm(RecoveryCapacityTest t, WorkflowExecutor a) {
                t.store.getInstanceHooks.put("wf-1", _ -> {
                    throw new StoreUnavailableException("injected", new IllegalStateException("db down"));
                });
            }
        },
        /** W8: the workflow factory throws (isolated per instance by the pass). */
        FACTORY_THROWS {
            @Override
            Map<String, WorkflowRegistration> registrations(RecoveryCapacityTest t, WorkflowExecutor a) {
                return t.registrations(a, new IllegalStateException("injected factory failure"));
            }
        },
        /** W8: an Error between admission and the thread start. */
        ERROR_AFTER_ADMISSION {
            @Override
            void arm(RecoveryCapacityTest t, WorkflowExecutor a) {
                a.afterLaunchAdmission = () -> {
                    throw new AssertionError("injected after admission");
                };
            }
        };

        final int slots;
        final int headroom;
        final boolean lockHeld;

        Window() {
            this(1, 2, false);
        }

        Window(int slots, int headroom, boolean lockHeld) {
            this.slots = slots;
            this.headroom = headroom;
            this.lockHeld = lockHeld;
        }

        /** Builds the node, seeds wf-1, arms the fault and runs one gated pass, whatever it throws. */
        WorkflowExecutor drive(RecoveryCapacityTest t) {
            var a = node(t);
            seedRecoverable(t.store, "wf-1", "orders");
            arm(t, a);
            try {
                a.recoveryPass(registrations(t, a));
            } catch (Throwable expectedForThrowingWindows) {
                // W4, W7 and W8's Error end the pass; the assertions are what matter
            }
            return a;
        }

        WorkflowExecutor node(RecoveryCapacityTest t) {
            return t.node(1);
        }

        void arm(RecoveryCapacityTest t, WorkflowExecutor a) {
        }

        Map<String, WorkflowRegistration> registrations(RecoveryCapacityTest t, WorkflowExecutor a) {
            return t.registrations(a);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────

    private WorkflowExecutor node(int c) {
        return node(c, EngineObserver.NOOP);
    }

    private WorkflowExecutor node(int c, EngineObserver observer) {
        var builder = WorkflowExecutor.builder(store, serializer, SERVICE)
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .observer(observer)
                .shutdownTimeout(Duration.ofSeconds(2));
        if (c > 0) {
            builder.defaultTaskQueueLimits(new TaskQueueLimits(c, c));
        }
        var executor = builder.build();
        names.put(executor, String.valueOf((char) ('A' + nodes.size())));
        nodes.add(executor);
        return executor;
    }

    private Map<String, WorkflowRegistration> registrations(WorkflowExecutor executor) {
        return registrations(executor, null);
    }

    private Map<String, WorkflowRegistration> registrations(WorkflowExecutor executor,
                                                            @Nullable RuntimeException factoryFailure) {
        var proxy = work.proxy(executor, store, serializer, names.get(executor));
        return Map.of("WorkWorkflow", new WorkflowRegistration("WorkWorkflow", "orders", () -> {
            if (factoryFailure != null) {
                throw factoryFailure;
            }
            return new WorkWorkflow(proxy);
        }, workMethod()));
    }
}
