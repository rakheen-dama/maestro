package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.engine.LifecycleRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of Maestro's Kafka listener containers as one member of the engine's
 * {@link LifecycleRegistry} (pilot readiness P1 task 5, Issue 69). Internal
 * until 1.0.
 *
 * <p>The group {@linkplain #join joins} the registry before any of its
 * containers starts, and leaves once every container it started has stopped.
 * The engine's shutdown asks it to stop at the start of its drain:
 * {@link #requestStop()} calls Spring Kafka's asynchronous
 * {@code stop(Runnable)} on each container — it returns at once and never
 * interrupts; each consumer finishes the record in hand, leaves its consumer
 * group (its uncommitted offsets go to a peer) and runs the callback — and
 * shutdown waits for the group on its one deadline. A container offered after
 * the stop is never started.
 *
 * <p>Without a registry (no engine in the context) the group still tracks its
 * containers; {@link #requestStop()} from a bean's {@code destroy()} then stops
 * them without waiting.
 *
 * <p>Thread-safe: the state is guarded by this object's monitor; no container
 * call is made while holding it.
 */
public final class KafkaContainerGroup implements LifecycleRegistry.Member {

    private static final Logger logger = LoggerFactory.getLogger(KafkaContainerGroup.class);

    private final String name;
    /** Containers started and not yet reported stopped, in start order. */
    private final Set<MessageListenerContainer> live = new LinkedHashSet<>();
    /** Containers admitted and still inside their {@code start()}. */
    private final Set<MessageListenerContainer> starting = new LinkedHashSet<>();
    private LifecycleRegistry.@Nullable Membership membership;
    private boolean stopRequested;
    private boolean left;
    /** Counted down once every container has stopped after a stop request. */
    private final java.util.concurrent.CountDownLatch allStopped = new java.util.concurrent.CountDownLatch(1);

    /**
     * @param name the member's name in shutdown's log, e.g. {@code "kafka-signal-containers"}
     */
    public KafkaContainerGroup(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Joins {@code registry} as a {@link LifecycleRegistry.Kind#CONSUMER}, once.
     * A refused join (shutdown has begun) is a stop: no container is started
     * from then on.
     *
     * @param registry the engine's registry
     * @return {@code false} if the registry refused the group
     */
    public boolean join(LifecycleRegistry registry) {
        synchronized (this) {
            if (membership != null || stopRequested) {
                return membership != null;
            }
        }
        var joined = registry.join(LifecycleRegistry.Kind.CONSUMER, this);
        synchronized (this) {
            if (joined.isEmpty()) {
                stopRequested = true;
                return false;
            }
            membership = joined.get();
        }
        return true;
    }

    /**
     * Starts {@code container} as part of this group, unless the group was
     * asked to stop: then it is never started.
     *
     * @param container the container to start
     * @return whether it was started
     * @throws RuntimeException whatever {@code container.start()} throws
     */
    public boolean start(MessageListenerContainer container) {
        synchronized (this) {
            if (stopRequested) {
                return false;
            }
            starting.add(container);
        }
        try {
            container.start();
        } catch (RuntimeException | Error e) {
            boolean leaveNow;
            synchronized (this) {
                starting.remove(container);
                leaveNow = shouldLeave();
            }
            if (leaveNow) {
                leave();
            }
            throw e;
        }
        boolean stopNow;
        synchronized (this) {
            starting.remove(container);
            live.add(container);
            stopNow = stopRequested; // asked while it was starting: stop it now
        }
        if (stopNow) {
            stop(container);
        }
        return true;
    }

    /**
     * Asks every container to stop and returns at once: Spring Kafka's
     * asynchronous {@code stop(Runnable)}, never an interrupt. The group
     * leaves the registry when the last one has stopped. Idempotent.
     */
    @Override
    public void requestStop() {
        List<MessageListenerContainer> toStop;
        boolean leaveNow;
        synchronized (this) {
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            toStop = List.copyOf(live);
            leaveNow = shouldLeave();
        }
        if (!toStop.isEmpty()) {
            logger.info("Stopping {} Maestro Kafka listener container(s) ({})", toStop.size(), name);
        }
        for (var container : toStop) {
            stop(container);
        }
        if (leaveNow) {
            leave();
        }
    }

    /** @return whether the group is a member of an engine's registry (its shutdown then waits for it) */
    public synchronized boolean joined() {
        return membership != null;
    }

    /**
     * Waits at most {@code bound} for every container to have stopped after
     * {@link #requestStop()} — for a bean's {@code destroy()} when no engine
     * owns the group: without an engine there is no shutdown deadline to take
     * the wait from, and this keeps the wait the blocking {@code stop()} had.
     * Never called when the group is {@link #joined()}.
     *
     * @param bound the longest to wait
     * @return whether every container has stopped
     */
    public boolean awaitStopped(java.time.Duration bound) {
        try {
            return allStopped.await(bound.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return allStopped.getCount() == 0;
        }
    }

    /** @return the containers started and not yet stopped, in start order (diagnostics and tests) */
    public synchronized List<MessageListenerContainer> running() {
        return List.copyOf(live);
    }

    private void stop(MessageListenerContainer container) {
        try {
            container.stop(() -> stopped(container));
        } catch (RuntimeException e) {
            logger.warn("Error stopping a Maestro Kafka listener container ({}): {}", name, e.toString());
            stopped(container);
        }
    }

    private void stopped(MessageListenerContainer container) {
        boolean leaveNow;
        synchronized (this) {
            live.remove(container);
            leaveNow = shouldLeave();
        }
        if (leaveNow) {
            leave();
        }
    }

    /** Guarded by this: asked to stop, nothing live or starting, not left yet. */
    private boolean shouldLeave() {
        return stopRequested && live.isEmpty() && starting.isEmpty() && !left;
    }

    private void leave() {
        LifecycleRegistry.Membership toLeave;
        synchronized (this) {
            if (left) {
                return;
            }
            left = true;
            toLeave = membership;
        }
        allStopped.countDown();
        if (toLeave != null) {
            toLeave.leave();
        }
    }
}
