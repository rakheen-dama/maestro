package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KafkaContainerGroup} against an engine's lifecycle registry, with
 * stand-in containers whose asynchronous {@code stop(Runnable)} the test
 * completes: the group is a CONSUMER member, the engine's shutdown asks it to
 * stop and waits until the last container's stop callback — and a container
 * offered after the stop is never started.
 */
@Timeout(30)
class KafkaContainerGroupTest {

    private final WorkflowExecutor executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                    new PayloadSerializer(JsonMapper.builder().build()), "svc")
            .distributedLock(new InMemoryDistributedLock())
            .lifecycleEventsEnabled(false)
            .shutdownTimeout(Duration.ofSeconds(10))
            .build();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void shutdownStopsEveryContainerAndWaitsForTheLastStopCallback() throws Exception {
        var group = new KafkaContainerGroup("test-containers");
        assertTrue(group.join(executor.lifecycle()));
        var a = new FakeContainer();
        var b = new FakeContainer();
        assertTrue(group.start(a.proxy()));
        assertTrue(group.start(b.proxy()));

        var shutter = Thread.ofVirtual().start(executor::shutdown);
        assertTrue(a.stopAsked.await(5, TimeUnit.SECONDS) && b.stopAsked.await(5, TimeUnit.SECONDS),
                "both containers were asked to stop, asynchronously");
        a.finishStop();
        assertFalse(shutter.join(Duration.ofMillis(300)), "shutdown waits for the last container");
        b.finishStop();
        assertTrue(shutter.join(Duration.ofSeconds(5)), "shutdown returns once every container stopped");
        assertEquals(List.of(), group.running());

        var late = new FakeContainer();
        assertFalse(group.start(late.proxy()), "a container offered after the stop is never started");
        assertFalse(late.started.get());
    }

    @Test
    void aGroupRefusedByAClosedRegistryStartsNothing() {
        executor.shutdown();
        var group = new KafkaContainerGroup("late-containers");
        assertFalse(group.join(executor.lifecycle()));
        var container = new FakeContainer();
        assertFalse(group.start(container.proxy()));
        assertFalse(container.started.get());
    }

    /**
     * Review L1: a group the registry refused started nothing, so a bean's
     * {@code destroy()} — {@code requestStop()} then {@code awaitStopped} — must
     * not wait its 10 s bound for it.
     */
    @Test
    void aRefusedGroupIsAlreadyStoppedSoDestroyDoesNotWait() {
        executor.shutdown();
        var group = new KafkaContainerGroup("refused-containers");
        assertFalse(group.join(executor.lifecycle()));
        group.requestStop();
        var start = System.nanoTime();
        assertTrue(group.awaitStopped(Duration.ofSeconds(10)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(500)) < 0,
                "no wait for a group that never started a container");
    }

    /**
     * A stop that reaches the member between the registry's admission and the
     * group recording its membership (shutdown's S2 racing the join) still
     * makes the group leave, so shutdown does not wait out its deadline for a
     * member that started nothing.
     */
    @Test
    void aStopRacingTheJoinStillLeavesTheRegistry() {
        var group = new KafkaContainerGroup("racing-join");
        var membership = org.mockito.Mockito.mock(io.b2mash.maestro.core.engine.LifecycleRegistry.Membership.class);
        var registry = org.mockito.Mockito.mock(io.b2mash.maestro.core.engine.LifecycleRegistry.class);
        org.mockito.Mockito.when(registry.join(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    ((io.b2mash.maestro.core.engine.LifecycleRegistry.Member) call.getArgument(1)).requestStop();
                    return java.util.Optional.of(membership);
                });
        assertTrue(group.join(registry));
        org.mockito.Mockito.verify(membership).leave();
    }

    /**
     * A container started before the group joined (an embedder subscribing
     * first) is stopped when the registry refuses the join, and the group is
     * stopped once its callback has run.
     */
    @Test
    void aRefusedJoinStopsWhatTheGroupAlreadyStarted() {
        var group = new KafkaContainerGroup("early-containers");
        var early = new FakeContainer();
        assertTrue(group.start(early.proxy()));
        executor.shutdown();
        assertFalse(group.join(executor.lifecycle()));
        assertEquals(0, early.stopAsked.getCount(), "the refused join asked the running container to stop");
        assertFalse(group.awaitStopped(Duration.ofMillis(100)), "not stopped before its callback");
        early.finishStop();
        assertTrue(group.awaitStopped(Duration.ofSeconds(1)));
    }

    @Test
    void aContainerStillStartingWhenTheStopArrivesIsStoppedOnceStarted() throws Exception {
        var group = new KafkaContainerGroup("racing-containers");
        assertTrue(group.join(executor.lifecycle()));
        var slow = new FakeContainer();
        slow.startGate = new CountDownLatch(1);
        var starter = Thread.ofVirtual().start(() -> group.start(slow.proxy()));
        assertTrue(slow.inStart.await(5, TimeUnit.SECONDS));

        var shutter = Thread.ofVirtual().start(executor::shutdown);
        assertFalse(shutter.join(Duration.ofMillis(300)), "shutdown waits for a container still starting");
        slow.startGate.countDown();
        assertTrue(slow.stopAsked.await(5, TimeUnit.SECONDS), "stopped as soon as its start returned");
        slow.finishStop();
        assertTrue(starter.join(Duration.ofSeconds(5)) && shutter.join(Duration.ofSeconds(5)));
    }

    // ── PR #78 review ──────────────────────────────────────────────────

    /**
     * 4634059410: two concurrent joins reach the registry once; a second
     * membership would overwrite the first, which then never leaves.
     */
    @Test
    void concurrentJoinsAdmitTheGroupOnce() throws Exception {
        var group = new KafkaContainerGroup("double-join");
        var inJoin = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var joins = new java.util.concurrent.atomic.AtomicInteger();
        var registry = org.mockito.Mockito.mock(io.b2mash.maestro.core.engine.LifecycleRegistry.class);
        var membership = org.mockito.Mockito.mock(io.b2mash.maestro.core.engine.LifecycleRegistry.Membership.class);
        org.mockito.Mockito.when(registry.join(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    joins.incrementAndGet();
                    inJoin.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return java.util.Optional.of(membership);
                });
        var first = Thread.ofVirtual().start(() -> group.join(registry));
        assertTrue(inJoin.await(5, TimeUnit.SECONDS));
        var second = Thread.ofVirtual().start(() -> group.join(registry));
        second.join(Duration.ofMillis(300)); // give a racing second admission time to happen
        release.countDown();
        assertTrue(first.join(Duration.ofSeconds(5)) && second.join(Duration.ofSeconds(5)));
        assertEquals(1, joins.get(), "admitted once");
    }

    /**
     * 4634059419: a container whose start() throws after some of its
     * consumers are up stays tracked and is stopped; the exception still
     * propagates, and the group leaves only once that stop has completed.
     */
    @Test
    void aContainerWhoseStartThrowsIsStoppedAndWaitedFor() throws Exception {
        var group = new KafkaContainerGroup("failed-start");
        assertTrue(group.join(executor.lifecycle()));
        var broken = new FakeContainer();
        broken.startThrowsWhileRunning = true;
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> group.start(broken.proxy()));
        assertTrue(thrown.getMessage().contains("later child"));
        assertTrue(broken.stopAsked.await(5, TimeUnit.SECONDS), "the half-started container was asked to stop");

        var shutter = Thread.ofVirtual().start(executor::shutdown);
        assertFalse(shutter.join(Duration.ofMillis(300)), "shutdown waits for its consumers to end");
        broken.finishStop();
        assertTrue(shutter.join(Duration.ofSeconds(5)));
    }

    /**
     * 4634059425: a stop(Runnable) that throws after registering its callback
     * does not count the container stopped; the callback does.
     */
    @Test
    void aStopThatThrowsIsNotTakenForAStoppedContainer() throws Exception {
        var group = new KafkaContainerGroup("throwing-stop");
        assertTrue(group.join(executor.lifecycle()));
        var container = new FakeContainer();
        container.stopThrowsAfterRegistering = true;
        assertTrue(group.start(container.proxy()));

        var shutter = Thread.ofVirtual().start(executor::shutdown);
        assertTrue(container.stopAsked.await(5, TimeUnit.SECONDS));
        assertFalse(shutter.join(Duration.ofMillis(300)), "still running its record: not stopped yet");
        container.finishStop();
        assertTrue(shutter.join(Duration.ofSeconds(5)));
    }

    /**
     * PR #78 review 4634444589: a second stop(Runnable) on a container whose
     * first stop is still in progress calls back at once (Spring Kafka), so the
     * group must ask each container to stop exactly once — here a failed start
     * stopped it, then shutdown's requestStop() would have asked again and left
     * the registry while its consumers were still ending.
     */
    @Test
    void eachContainerIsAskedToStopExactlyOnce() throws Exception {
        var group = new KafkaContainerGroup("stop-once");
        assertTrue(group.join(executor.lifecycle()));
        var broken = new FakeContainer();
        broken.startThrowsWhileRunning = true;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> group.start(broken.proxy()));
        assertTrue(broken.stopAsked.await(5, TimeUnit.SECONDS));

        var shutter = Thread.ofVirtual().start(executor::shutdown);
        assertFalse(shutter.join(Duration.ofMillis(300)), "still ending its consumers: the group must not have left");
        assertEquals(1, broken.calls.stream().filter("stop"::equals).count(), "one stop call: " + broken.calls);
        broken.finishStop();
        assertTrue(shutter.join(Duration.ofSeconds(5)));
    }

    /** A stand-in container: start/stop recorded; the stop's callback runs when the test says. */
    static final class FakeContainer {
        final AtomicBoolean started = new AtomicBoolean();
        final CountDownLatch inStart = new CountDownLatch(1);
        final CountDownLatch stopAsked = new CountDownLatch(1);
        volatile CountDownLatch startGate = new CountDownLatch(0);
        /** start() leaves it running (some child consumers up), then throws. */
        volatile boolean startThrowsWhileRunning;
        /** stop(Runnable) registers the callback, then throws. */
        volatile boolean stopThrowsAfterRegistering;
        private final AtomicReference<Runnable> callback = new AtomicReference<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();

        /** The consumers have ended: runs the stop callback. */
        void finishStop() {
            started.set(false);
            callback.get().run();
        }

        MessageListenerContainer proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                calls.add(method.getName());
                switch (method.getName()) {
                    case "start" -> {
                        inStart.countDown();
                        startGate.await(10, TimeUnit.SECONDS);
                        started.set(true);
                        if (startThrowsWhileRunning) {
                            throw new IllegalStateException("a later child failed to start (test)");
                        }
                        return null;
                    }
                    case "stop" -> {
                        if (args != null && args.length == 1 && args[0] instanceof Runnable cb) {
                            if (!started.get()) {
                                cb.run();
                                return null;
                            }
                            callback.set(cb);
                            // As Spring Kafka 4.0.4: the first stop sets running=false at
                            // once while its consumers still end; a second stop(Runnable)
                            // on a container no longer running calls back immediately
                            // (AbstractMessageListenerContainer.stop) — PR #78 review 4634444589.
                            started.set(false);
                            stopAsked.countDown();
                            if (stopThrowsAfterRegistering) {
                                throw new IllegalStateException("wakeup failed (test)");
                            }
                        }
                        return null;
                    }
                    case "isRunning" -> {
                        return started.get();
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "FakeContainer";
                    }
                    default -> {
                        if (method.isDefault()) {
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        }
                        return null;
                    }
                }
            };
            return (MessageListenerContainer) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{MessageListenerContainer.class}, handler);
        }
    }
}
