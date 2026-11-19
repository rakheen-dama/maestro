package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.RunCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LifecycleRegistry} on its own: admission, the close snapshot, the waits. */
@Timeout(30)
class LifecycleRegistryTest {

    private final LifecycleRegistry registry = new LifecycleRegistry();

    @Test
    void aJoinWhileOpenIsInTheCloseSnapshotAndAJoinAfterItIsRefused() {
        var run = registry.joinRun("wf-1", new RunCancellation());
        var loop = registry.join(LifecycleRegistry.Kind.LOOP, new NamedMember("timer-poller")).orElseThrow();

        var snapshot = registry.close();

        assertEquals(List.of(run, loop), snapshot);
        assertEquals(LifecycleRegistry.State.DRAINING, registry.state());
        assertNull(registry.joinRun("wf-2", new RunCancellation()));
        assertTrue(registry.join(LifecycleRegistry.Kind.TASK, new NamedMember("late")).isEmpty());
        registry.terminate();
        assertEquals(LifecycleRegistry.State.CLOSED, registry.state());
        assertNull(registry.joinRun("wf-3", new RunCancellation()));
    }

    @Test
    void leaveIsIdempotentAndOnlyRemovesItsOwnMembership() {
        var first = registry.joinRun("wf-twin", new RunCancellation());
        var second = registry.joinRun("wf-twin", new RunCancellation());
        assertNotNull(first);

        first.leave();
        first.leave();

        assertEquals(List.of(second), registry.remaining());
    }

    @Test
    void requestStopAsksARunForShutdownAndAnyOtherMemberThroughItsCallback() {
        var cancellation = new RunCancellation();
        var member = new NamedMember("sweeper");
        registry.joinRun("wf-1", cancellation);
        registry.join(LifecycleRegistry.Kind.LOOP, member);

        registry.close().forEach(LifecycleRegistry.Membership::requestStop);

        assertEquals(RunCancellation.Cause.SHUTDOWN, cancellation.cause());
        assertEquals(1, member.stops.get());
    }

    @Test
    void awaitNoRunsReturnsWhenTheLastRunLeavesAndIgnoresOtherMembers() throws Exception {
        var run = registry.joinRun("wf-1", new RunCancellation());
        var loop = registry.join(LifecycleRegistry.Kind.LOOP, new NamedMember("loop")).orElseThrow();
        registry.close();
        var deadline = ShutdownDeadline.start(Duration.ofSeconds(10));
        Thread.ofVirtual().start(() -> {
            pause(100);
            run.leave();
        });

        var start = System.nanoTime();
        assertTrue(registry.awaitNoRuns(deadline));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000);

        assertFalse(registry.awaitNoMembers(ShutdownDeadline.start(Duration.ofMillis(50))), "the loop is still in");
        loop.leave();
        assertTrue(registry.awaitNoMembers(deadline));
    }

    @Test
    void aWaitEndsAtTheDeadline() {
        registry.joinRun("wf-stuck", new RunCancellation());
        registry.close();

        var start = System.nanoTime();
        assertFalse(registry.awaitNoRuns(ShutdownDeadline.start(Duration.ofMillis(200))));
        var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(tookMs >= 150 && tookMs < 5_000, "took " + tookMs + " ms");
    }

    @Test
    void anInterruptedWaitEndsAtOnceAndKeepsTheFlag() throws Exception {
        registry.joinRun("wf-stuck", new RunCancellation());
        registry.close();
        var result = new ConcurrentLinkedQueue<Boolean>();
        var waiter = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            result.add(registry.awaitNoRuns(ShutdownDeadline.start(Duration.ofSeconds(20))));
            result.add(Thread.currentThread().isInterrupted());
            // The next wait ends at once too.
            result.add(registry.awaitNoMembers(ShutdownDeadline.start(Duration.ofSeconds(20))));
        });

        assertTrue(waiter.join(Duration.ofSeconds(5)));
        assertEquals(List.of(false, true, false), List.copyOf(result));
    }

    /**
     * Issue 80's property: joins racing {@code close()} are each either in its
     * snapshot or refused — never admitted and missing from it.
     */
    @Test
    void everyJoinRacingCloseIsEitherInTheSnapshotOrRefused() throws Exception {
        for (var round = 0; round < 200; round++) {
            var racing = new LifecycleRegistry();
            var go = new CountDownLatch(1);
            var admitted = new ConcurrentLinkedQueue<LifecycleRegistry.Membership>();
            var threads = new ArrayList<Thread>();
            for (var i = 0; i < 16; i++) {
                var id = "wf-" + i;
                threads.add(Thread.ofVirtual().start(() -> {
                    awaitQuietly(go);
                    var membership = racing.joinRun(id, new RunCancellation());
                    if (membership != null) {
                        admitted.add(membership);
                    }
                }));
            }
            go.countDown();
            var snapshot = new HashSet<>(racing.close());
            for (var thread : threads) {
                assertTrue(thread.join(Duration.ofSeconds(5)));
            }
            assertEquals(snapshot, new HashSet<>(admitted), "round " + round);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class NamedMember implements LifecycleRegistry.Member {
        private final String name;
        final AtomicInteger stops = new AtomicInteger();

        NamedMember(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void requestStop() {
            stops.incrementAndGet();
        }
    }
}
