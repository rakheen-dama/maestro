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

    /** A stand-in container: start/stop recorded; the stop's callback runs when the test says. */
    static final class FakeContainer {
        final AtomicBoolean started = new AtomicBoolean();
        final CountDownLatch inStart = new CountDownLatch(1);
        final CountDownLatch stopAsked = new CountDownLatch(1);
        volatile CountDownLatch startGate = new CountDownLatch(0);
        private final AtomicReference<Runnable> callback = new AtomicReference<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();

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
                        return null;
                    }
                    case "stop" -> {
                        if (args != null && args.length == 1 && args[0] instanceof Runnable cb) {
                            if (!started.get()) {
                                cb.run();
                                return null;
                            }
                            callback.set(cb);
                            stopAsked.countDown();
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
