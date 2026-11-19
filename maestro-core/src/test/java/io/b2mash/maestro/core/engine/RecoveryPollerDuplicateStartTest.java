package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.LockAcquisitionException;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #76 review 4629668484: a second {@code startRecoveryPoller} is refused
 * and must not replace the body the running loop uses. Before, the new body
 * was published before {@code supervise} refused the duplicate name, so a
 * deferred start consulted the new body's registrations while the old body
 * ran — a start of a type only the refused body registers was persisted as
 * deferred, and nothing on the node would ever adopt it.
 *
 * <p><b>Thread safety:</b> per-test fixtures.
 */
class RecoveryPollerDuplicateStartTest {

    private final VersionedInMemoryStore store = new VersionedInMemoryStore();
    private final InMemoryDistributedLock lock = new InMemoryDistributedLock();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(
                    store, new PayloadSerializer(JsonMapper.builder().build()), "svc")
            .distributedLock(lock)
            .lifecycleEventsEnabled(false)
            .build();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** Type A. */
    @DurableWorkflow(name = "A")
    public static final class TypeA {
        /** @return done */
        @WorkflowMethod
        public String run() {
            return "a";
        }
    }

    /** Type B. */
    @DurableWorkflow(name = "B")
    public static final class TypeB {
        /** @return done */
        @WorkflowMethod
        public String run() {
            return "b";
        }
    }

    @Test
    void aRefusedSecondStartDoesNotReplaceTheRunningBody() throws Exception {
        var a = new WorkflowRegistration("A", "default", TypeA::new, TypeA.class.getMethod("run"));
        var b = new WorkflowRegistration("B", "default", TypeB::new, TypeB.class.getMethod("run"));
        executor.startRecoveryPoller(Map.of("A", a), Duration.ofHours(1));
        assertThrows(IllegalStateException.class,
                () -> executor.startRecoveryPoller(Map.of("B", b), Duration.ofHours(1)),
                "one recovery poller per executor");

        // B's lock is held elsewhere: the start may be deferred only if the
        // RUNNING poller registers B. It does not, so the start is refused and
        // nothing is persisted.
        assertTrue(lock.tryAcquire("maestro:lock:workflow:wf-b", Duration.ofMinutes(1)).isPresent());
        assertThrows(LockAcquisitionException.class,
                () -> executor.startWorkflow("wf-b", "B", "default", null, TypeB::new, TypeB.class.getMethod("run")));
        assertTrue(store.getInstance("wf-b").isEmpty(), "no row a poller that does not register B would skip");
    }
}
