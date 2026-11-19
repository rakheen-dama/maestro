package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code maestroLiveness} (design note §3, Q1): {@code DOWN} only for what a
 * restart fixes and nothing else does — a supervised loop that died — and
 * never for a stopped, stopping or finished loop, so a draining pod stays live.
 * It reads snapshots only, never the store.
 */
class MaestroLivenessHealthIndicatorTest {

    private final List<WorkflowExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(WorkflowExecutor::shutdown);
    }

    private WorkflowExecutor executor(WorkflowStore store) {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), "svc")
                .distributedLock(new InMemoryDistributedLock()).lifecycleEventsEnabled(false).build();
        executors.add(executor);
        return executor;
    }

    @Test
    void aDeadLoopIsDownAStoppedLoopIsUp() {
        var dying = executor(new InMemoryWorkflowStore());
        dying.supervise(LoopSpec.of("doomed", "test-doomed", LoopRole.HOUSEKEEPING, Duration.ofMillis(10)), ctx -> {
            throw new ExecutorShutdownException("a control-flow Error ends the loop (test)");
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> dying.loops().stream()
                .anyMatch(l -> l.name().equals("doomed") && l.state() == LoopSnapshot.State.DEAD));
        var dead = new MaestroLivenessHealthIndicator(dying).health();
        assertThat(dead.getStatus()).isEqualTo(Status.DOWN);
        assertThat(dead.getDetails()).containsEntry("dead", List.of("doomed"));

        var stopped = executor(new InMemoryWorkflowStore());
        stopped.startSignalJanitor(Duration.ofHours(1), Duration.ofDays(7));
        stopped.shutdown();
        assertThat(new MaestroLivenessHealthIndicator(stopped).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void neverTouchesTheStore() {
        var calls = new AtomicInteger();
        var delegate = new InMemoryWorkflowStore();
        var counting = (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[] {WorkflowStore.class}, (self, method, args) -> {
                    calls.incrementAndGet();
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var executor = executor(counting);
        var before = calls.get();

        var health = new MaestroLivenessHealthIndicator(executor).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(calls.get()).as("no store call").isEqualTo(before);
    }
}
