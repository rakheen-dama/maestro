package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.WorkflowInstantiationException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow factory that throws a {@link LinkageError} — a bean whose class
 * cannot be loaded or initialised — is isolated per instance, like a factory
 * that throws a {@code RuntimeException} (R44): the recovery pass skips that
 * instance and still recovers the ones behind it. Before, the
 * {@code LinkageError} ended the pass at the same keyset position on every
 * pass, so nothing after it was ever recovered.
 */
class RecoveryFactoryLinkageErrorTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final VersionedInMemoryStore store = new VersionedInMemoryStore();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock())
            .lifecycleEventsEnabled(false)
            .build();
    private final CountDownLatch healthyRan = new CountDownLatch(1);
    private int seeded;

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void aFactoryLinkageErrorSkipsThatInstanceAndThePassRecoversTheOneBehindIt() throws Exception {
        seed("broken-wf", "BrokenWorkflow");                 // scanned first
        seed("healthy-wf", "HealthyWorkflow");

        var pass = executor.recoveryPass(Map.of(
                "BrokenWorkflow", registration("BrokenWorkflow", () -> {
                    throw new NoClassDefFoundError("com/example/Missing");
                }),
                "HealthyWorkflow", registration("HealthyWorkflow", () -> new HealthyWorkflow(healthyRan))));

        assertEquals(1, pass.adopted(), "the healthy workflow behind the broken one is adopted in the same pass");
        assertEquals(Map.of("BrokenWorkflow", 1), pass.skippedByType());
        assertTrue(healthyRan.await(5, TimeUnit.SECONDS), "the adopted workflow ran");
    }

    @Test
    void aFactoryLinkageErrorIsWrappedLikeAFactoryRuntimeException() {
        seed("broken-wf", "BrokenWorkflow");
        var instance = store.getInstance("broken-wf").orElseThrow();
        var linkage = new ExceptionInInitializerError("static init failed");

        var thrown = assertThrows(WorkflowInstantiationException.class, () -> executor.resumeWorkflow(
                instance, () -> {
                    throw linkage;
                }, HealthyWorkflow.method()));

        assertInstanceOf(ExceptionInInitializerError.class, thrown.getCause());
    }

    @Test
    void anyOtherFactoryErrorStillEndsThePass() {
        seed("broken-wf", "BrokenWorkflow");
        seed("healthy-wf", "HealthyWorkflow");

        assertThrows(AssertionError.class, () -> executor.recoveryPass(Map.of(
                "BrokenWorkflow", registration("BrokenWorkflow", () -> {
                    throw new AssertionError("a bug, not a broken bean");
                }),
                "HealthyWorkflow", registration("HealthyWorkflow", () -> new HealthyWorkflow(healthyRan)))));
        assertFalse(executor.isRunning("healthy-wf"), "the pass ended at the broken instance");
    }

    private static WorkflowRegistration registration(String type, Supplier<Object> factory) {
        return new WorkflowRegistration(type, "default", factory, HealthyWorkflow.method());
    }

    /** Seeds a RUNNING instance; each one starts after the previous, so seed order is scan order. */
    private void seed(String workflowId, String workflowType) {
        var startedAt = Instant.now().minus(Duration.ofMinutes(10)).plusSeconds(seeded++);
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType(workflowType)
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(startedAt)
                .updatedAt(startedAt)
                .version(0)
                .build());
    }

    /** Counts down {@code ran} when it runs. */
    @DurableWorkflow(name = "HealthyWorkflow")
    public static class HealthyWorkflow {

        private final CountDownLatch ran;

        HealthyWorkflow(CountDownLatch ran) {
            this.ran = ran;
        }

        static java.lang.reflect.Method method() {
            try {
                return HealthyWorkflow.class.getMethod("run");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        /** @return a fixed result */
        @WorkflowMethod
        public String run() {
            ran.countDown();
            return "done";
        }
    }
}
