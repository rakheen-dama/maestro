package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowExecutor#supervise} and {@link WorkflowExecutor#loops()}: a
 * loop gets the executor's observer, is listed, has a unique name, and is
 * stopped by {@code shutdown()} — and the three migrated engine loops are
 * listed under their fixed names.
 */
class WorkflowExecutorSuperviseTest {

    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                    new PayloadSerializer(JsonMapper.builder().build()), "svc")
            .distributedLock(new InMemoryDistributedLock())
            .lifecycleEventsEnabled(false)
            .observer(observer)
            .build();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void aSupervisedLoopIsListedReportsThroughTheExecutorsObserverAndShutdownStopsIt() {
        var spec = LoopSpec.of("custom-loop", "maestro-custom-loop", LoopRole.HOUSEKEEPING, Duration.ofMillis(20));
        executor.supervise(spec, ctx -> {
            throw new AssertionError("every iteration fails");
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> !observer.loopErrors().isEmpty());
        assertEquals("custom-loop", observer.loopErrors().getFirst().loop());
        assertEquals(List.of("custom-loop"), executor.loops().stream().map(LoopSnapshot::name).toList());
        assertThrows(IllegalStateException.class, () -> executor.supervise(spec, ctx -> Next.afterPeriod()),
                "a loop name is unique on an executor");

        executor.shutdown();

        assertEquals(LoopSnapshot.State.STOPPED, executor.loops().getFirst().state());
        assertThrows(IllegalStateException.class,
                () -> executor.supervise(spec.withInitialDelay(Duration.ZERO), ctx -> Next.afterPeriod()));
    }

    @Test
    void theMigratedEngineLoopsAreListedUnderTheirFixedNames() {
        executor.startSignalJanitor(Duration.ofHours(1), Duration.ofDays(7));
        executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(Duration.ofHours(1),
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), 10, 1));
        executor.startOneShotRecovery(Map.of(), Duration.ofHours(1));

        await().atMost(Duration.ofSeconds(5)).until(() -> executor.loops().stream()
                .anyMatch(l -> l.name().equals("startup-recovery") && l.state() == LoopSnapshot.State.DONE));
        var byName = executor.loops().stream().collect(java.util.stream.Collectors.toMap(
                LoopSnapshot::name, l -> l));
        assertEquals(List.of("retention-sweeper", "signal-janitor", "startup-recovery"),
                executor.loops().stream().map(LoopSnapshot::name).toList());
        assertEquals(LoopRole.HOUSEKEEPING, byName.get("signal-janitor").role());
        assertEquals(LoopRole.HOUSEKEEPING, byName.get("retention-sweeper").role());
        assertEquals(LoopRole.CRITICAL, byName.get("startup-recovery").role());
        await().atMost(Duration.ofSeconds(5)).until(() -> executor.loops().stream()
                .filter(l -> l.name().equals("retention-sweeper"))
                .anyMatch(l -> Boolean.TRUE.equals(l.notes().get("leader"))));
        assertTrue(executor.isSignalJanitorRunning() && executor.isRetentionSweeperRunning());
    }
}
