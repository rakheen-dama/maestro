package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.TaskQueueLimits;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the {@code maestro.wake.permits} gauge registered by
 * {@link MaestroEngineGauges}: it exists under that name and reads
 * {@link WorkflowExecutor#wakePermitCount()}.
 */
class MaestroEngineGaugesTest {

    @Test
    void wakePermitsGaugeIsRegisteredAgainstTheExecutor() {
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                new PayloadSerializer(JsonMapper.builder().build()), "svc").distributedLock(new InMemoryDistributedLock()).build();
        try {
            var registry = new SimpleMeterRegistry();
            new MaestroEngineGauges(registry, executor);
            assertEquals(0.0, registry.get("maestro.wake.permits").gauge().value());
            assertEquals(executor.wakePermitCount(), (int) registry.get("maestro.wake.permits").gauge().value());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * {@code maestro.recovery.deferred} reads {@link WorkflowExecutor#recoveryDeferredCount()}:
     * the recovery poller's first pass is a full gated pass, and at limits
     * (1, 1) it adopts 2 of 4 instances (one running, one queued) and defers 2.
     */
    @Test
    void recoveryDeferredGaugeReadsTheLatestFullPass() throws Exception {
        var store = new InMemoryWorkflowStore();
        var base = Instant.parse("2026-01-01T00:00:00Z");
        for (var i = 0; i < 4; i++) {
            store.createInstance(WorkflowInstance.builder()
                    .id(UUID.randomUUID()).workflowId("wf-" + i).runId(UUID.randomUUID())
                    .workflowType("HoldWorkflow").taskQueue("default").status(WorkflowStatus.RUNNING)
                    .serviceName("svc").eventSequence(0)
                    .startedAt(base.plusMillis(i)).updatedAt(base.plusMillis(i)).version(0)
                    .build());
        }
        var hold = new CountDownLatch(1);
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(JsonMapper.builder().build()), "svc").distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .defaultTaskQueueLimits(new TaskQueueLimits(1, 1))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        try {
            var registry = new SimpleMeterRegistry();
            new MaestroEngineGauges(registry, executor);
            assertEquals(0.0, registry.get("maestro.recovery.deferred").gauge().value(), "0 before the first pass");

            executor.startRecoveryPoller(Map.of("HoldWorkflow", new WorkflowRegistration("HoldWorkflow", "default",
                    () -> new HoldWorkflow(hold), HoldWorkflow.class.getMethod("run"))), Duration.ofHours(1));
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (executor.recoveryDeferredCount() != 2 && System.nanoTime() < deadline) {
                Thread.sleep(5); // a poll interval, not a wait for a condition
            }
            assertEquals(2.0, registry.get("maestro.recovery.deferred").gauge().value());
        } finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    /**
     * {@code maestro.observer.errors} (Issue 81) reads
     * {@link WorkflowExecutor#observerErrorCount()}: an observer callback that
     * throws is contained and counted by the executor, not through an
     * observer — the one that failed may be the Micrometer one.
     */
    @Test
    void observerErrorsCounterReadsTheExecutorsContainedFailures() {
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                        new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .observer(new EngineObserver() {
                    @Override
                    public void recoveryPass(int scanned, int adopted) {
                        throw new IllegalStateException("observer bug");
                    }
                })
                .build();
        try {
            var registry = new SimpleMeterRegistry();
            new MaestroEngineGauges(registry, executor);
            var counter = registry.find("maestro.observer.errors").functionCounter();
            assertNotNull(counter, "no maestro.observer.errors: " + registry.getMetersAsString());
            assertEquals(0.0, counter.count());

            executor.recoverWorkflows(Map.of());                  // emits recoveryPass once

            assertEquals(1.0, counter.count());
        } finally {
            executor.shutdown();
        }
    }

    /** A run that stays running until the test lets it end. */
    @DurableWorkflow(name = "HoldWorkflow")
    public static final class HoldWorkflow {
        private final CountDownLatch hold;

        HoldWorkflow(CountDownLatch hold) {
            this.hold = hold;
        }

        /** @return a marker, once the test lets the run end */
        @WorkflowMethod
        public String run() throws InterruptedException {
            hold.await(30, SECONDS);
            return "done";
        }
    }
}
