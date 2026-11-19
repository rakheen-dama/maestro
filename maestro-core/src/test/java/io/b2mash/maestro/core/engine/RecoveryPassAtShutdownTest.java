package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supervised recovery poller and the lifecycle registry together (merge of
 * pilot readiness P1 into P2): a recovery pass in flight when shutdown begins
 * adopts nothing once the registry is closed, and a pass stuck in its store
 * read at the deadline does not hold shutdown past its deadline; the poller's
 * stop still ends it once the read returns.
 *
 * <p><b>Thread safety:</b> per-test fixtures; the gates are latches.
 */
class RecoveryPassAtShutdownTest {

    private final CountDownLatch inRead = new CountDownLatch(1);
    private final CountDownLatch releaseRead = new CountDownLatch(1);
    private final AtomicInteger runs = new AtomicInteger();
    private final GatedStore store = new GatedStore();
    private @Nullable WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        releaseRead.countDown();
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** The first paged recovery read blocks until released. */
    final class GatedStore extends VersionedInMemoryStore {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public List<WorkflowInstance> getRecoverableInstances(String serviceName, @Nullable RecoveryCursor after,
                                                              int limit) {
            if (reads.incrementAndGet() == 1) {
                inRead.countDown();
                try {
                    assertTrue(releaseRead.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.getRecoverableInstances(serviceName, after, limit);
        }
    }

    /** Counts its runs; adopting the seeded instance would run it. */
    @DurableWorkflow(name = "Quick")
    public static final class Quick {
        private final AtomicInteger runs;

        Quick(AtomicInteger runs) {
            this.runs = runs;
        }

        /** @return done */
        @WorkflowMethod
        public String run() {
            runs.incrementAndGet();
            return "done";
        }
    }

    private WorkflowExecutor start(Duration shutdownTimeout) throws Exception {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID()).workflowId("wf-recoverable").runId(UUID.randomUUID())
                .workflowType("Quick").taskQueue("default").status(WorkflowStatus.RUNNING)
                .serviceName("svc").eventSequence(0).startedAt(now).updatedAt(now).version(0)
                .build());
        var built = WorkflowExecutor.builder(store, new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(shutdownTimeout)
                .build();
        executor = built;
        var registration = new WorkflowRegistration("Quick", "default", () -> new Quick(runs),
                Quick.class.getMethod("run"));
        built.startRecoveryPoller(Map.of("Quick", registration), Duration.ofHours(1));
        assertTrue(inRead.await(5, TimeUnit.SECONDS), "the first recovery pass reached its read");
        return built;
    }

    @Test
    void aPassInFlightWhenShutdownBeginsAdoptsNothing() throws Exception {
        var node = start(Duration.ofSeconds(5));
        var stopper = Thread.ofVirtual().start(node::shutdown);
        await("the registry closed").atMost(Duration.ofSeconds(5)).until(node::isShuttingDown);

        releaseRead.countDown();                              // the pass reads the row now
        stopper.join(Duration.ofSeconds(10));

        assertEquals(0, runs.get(), "a pass in flight at shutdown adopted nothing");
        var instance = store.getInstance("wf-recoverable").orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, instance.status());
        assertEquals(0, instance.version(), "no adoption CAS");
    }

    @Test
    void aPassStuckInItsReadDoesNotHoldShutdownPastItsDeadline() throws Exception {
        var node = start(Duration.ofMillis(500));
        var started = System.nanoTime();
        node.shutdown();
        var took = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0, "shutdown waited past its deadline: " + took);

        releaseRead.countDown();                              // the read returns after shutdown
        await("the poller ends after its read").atMost(Duration.ofSeconds(5)).until(() -> node.loops().stream()
                .filter(l -> l.name().equals(RecoveryPoller.LOOP_NAME)).allMatch(l -> l.state().ended()));
        assertEquals(0, runs.get(), "nothing adopted after shutdown");
        assertEquals(0, store.getInstance("wf-recoverable").orElseThrow().version());
    }
}
