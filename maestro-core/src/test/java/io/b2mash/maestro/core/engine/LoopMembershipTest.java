package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 3: every supervised loop is a member of the
 * executor's lifecycle registry — it joins before its thread starts and
 * leaves once that thread has ended (after its {@code onExit}, its end state
 * published) — so the registry is the one stop and wait path: a loop start
 * after {@code close()} is refused, and no loop can be started and forgotten.
 */
@Timeout(60)
class LoopMembershipTest {

    private static final Duration PERIOD = Duration.ofMillis(20);

    private WorkflowExecutor executor(Duration shutdownTimeout) {
        return WorkflowExecutor.builder(new InMemoryWorkflowStore(), new PayloadSerializer(JsonMapper.builder().build()),
                        "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(shutdownTimeout)
                .build();
    }

    @Test
    void aRunningLoopIsALoopMemberAndLeavesOnlyOnceItsThreadHasEnded() throws Exception {
        var executor = executor(Duration.ofSeconds(10));
        var exiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.supervise(LoopSpec.of("member-loop", "maestro-member-loop", LoopRole.HOUSEKEEPING, PERIOD),
                new io.b2mash.maestro.core.loop.LoopBody() {
                    @Override
                    public Next iterate(io.b2mash.maestro.core.loop.LoopContext ctx) {
                        return Next.afterPeriod();
                    }

                    @Override
                    public void onExit(io.b2mash.maestro.core.loop.LoopContext ctx) {
                        exiting.countDown();
                        awaitQuietly(release); // the loop's last act: still holding what it holds
                    }
                });

        var members = executor.lifecycle().remaining();
        assertEquals(1, members.size(), String.valueOf(members));
        assertEquals(LifecycleRegistry.Kind.LOOP, members.getFirst().kind());
        assertEquals("member-loop", members.getFirst().name());

        var shutter = Thread.ofVirtual().start(executor::shutdown);
        assertTrue(exiting.await(10, TimeUnit.SECONDS), "the loop was asked to stop and is in its onExit");
        assertFalse(shutter.join(Duration.ofMillis(300)), "shutdown waits for the loop's onExit");
        assertEquals(1, executor.lifecycle().remaining().size(), "still a member inside onExit");
        release.countDown();

        assertTrue(shutter.join(Duration.ofSeconds(10)));
        assertTrue(executor.lifecycle().remaining().isEmpty(), "the loop left");
        assertEquals(LoopSnapshot.State.STOPPED, loop(executor, "member-loop").state(),
                "its end state is published before it leaves");
    }

    /**
     * Starts racing {@code shutdown()}: each start either is refused — no
     * thread, nothing listed as running — or its loop is in {@code close()}'s
     * snapshot and has ended when {@code shutdown()} returns.
     */
    @Test
    void everyLoopStartRacingShutdownIsRefusedOrStoppedByIt() throws Exception {
        for (var round = 0; round < 50; round++) {
            var executor = executor(Duration.ofSeconds(10));
            var go = new CountDownLatch(1);
            var iterations = new AtomicInteger();
            var refused = new AtomicReference<@Nullable Throwable>();
            var starter = Thread.ofVirtual().start(() -> {
                awaitQuietly(go);
                try {
                    executor.supervise(LoopSpec.of("racer", "maestro-racer", LoopRole.HOUSEKEEPING, PERIOD), ctx -> {
                        iterations.incrementAndGet();
                        return Next.afterPeriod();
                    });
                } catch (IllegalStateException e) {
                    refused.set(e);
                }
            });
            var shutter = Thread.ofVirtual().start(() -> {
                awaitQuietly(go);
                executor.shutdown();
            });
            go.countDown();
            assertTrue(starter.join(Duration.ofSeconds(15)) && shutter.join(Duration.ofSeconds(15)), "round " + round);

            var snapshot = executor.loops().stream().filter(l -> l.name().equals("racer")).findFirst();
            if (refused.get() != null) {
                assertTrue(snapshot.isEmpty() || snapshot.get().state().ended(), "round " + round + ": " + snapshot);
            } else {
                assertTrue(snapshot.isPresent() && snapshot.get().state().ended(),
                        "round " + round + ": a loop started before close() is stopped by shutdown: " + snapshot);
            }
            assertTrue(executor.lifecycle().remaining().isEmpty(), "round " + round);
            var after = iterations.get();
            Thread.sleep(3 * PERIOD.toMillis());
            assertEquals(after, iterations.get(), "round " + round + ": no iteration after shutdown returned");
        }
    }

    private static LoopSnapshot loop(WorkflowExecutor executor, String name) {
        return executor.loops().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
