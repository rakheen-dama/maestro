package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two nodes racing one activity sequence end as lease fencing dictates, with
 * no per-activity lock (3.10).
 *
 * <p>Node A is inside the workflow's first activity when it loses its instance
 * lock (the row is taken away, as a TTL expiry would) and node B adopts the
 * workflow. B replays nothing, runs the first activity itself — the step runs
 * twice, which is why activities must be idempotent — runs the second and
 * completes. A's first activity then returns: its outcome append carries the
 * old lease epoch, is fenced, and A stands down {@code LEASE_FENCED} without
 * running the second activity. One result per sequence is persisted, B's.
 *
 * <p>Both nodes' activity proxies are handed the shared {@link DistributedLock};
 * the only keys either node ever asks it about are instance-lock keys.
 */
class TwoNodeActivityRaceTest {

    private static final String WORKFLOW_ID = "race-1";
    private static final String INSTANCE_KEY = "maestro:lock:workflow:" + WORKFLOW_ID;

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final VersionedInMemoryStore store = new VersionedInMemoryStore();
    private final KeyRecordingLock lock = new KeyRecordingLock();
    private final CountDownLatch gate = new CountDownLatch(1);
    private @Nullable WorkflowExecutor nodeA;
    private @Nullable WorkflowExecutor nodeB;

    @AfterEach
    void tearDown() {
        gate.countDown();
        if (nodeA != null) {
            nodeA.shutdown();
        }
        if (nodeB != null) {
            nodeB.shutdown();
        }
    }

    @Test
    @DisplayName("Two nodes racing one activity sequence: one result persisted, the loser stands down LEASE_FENCED, "
            + "and no activity lock is taken (3.10)")
    void twoNodesRacingOneActivitySequence_endAsFencingDictates_withoutAnActivityLock() throws Exception {
        var observerA = new RecordingEngineObserver();
        var observerB = new RecordingEngineObserver();
        nodeA = node(observerA);
        nodeB = node(observerB);
        var entered = new CountDownLatch(1);
        var activitiesA = new StampedActivities("A", entered, gate);
        var activitiesB = new StampedActivities("B", null, null);
        var proxyA = proxy(activitiesA);
        var proxyB = proxy(activitiesB);
        var method = RaceWorkflow.class.getMethod("run", String.class);

        var instanceId = nodeA.startWorkflow(WORKFLOW_ID, "RaceWorkflow", "default", "x",
                () -> new RaceWorkflow(proxyA), method);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "node A must be inside the first activity");

        // Node A's instance lock row goes away under it; node B adopts the workflow.
        lock.expire(INSTANCE_KEY);
        assertTrue(nodeB.resumeWorkflow(store.getInstance(WORKFLOW_ID).orElseThrow(),
                () -> new RaceWorkflow(proxyB), method), "node B must adopt the workflow");
        TestTerminalWait.awaitStatus(store, WORKFLOW_ID, WorkflowStatus.COMPLETED, Duration.ofSeconds(5));

        // Node A's in-flight first activity now returns.
        gate.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> !nodeA.isRunning(WORKFLOW_ID));

        assertEquals(List.of("first:x"), activitiesA.calls, "node A ran only the in-flight step");
        assertEquals(List.of("first:x", "second:x@B"), activitiesB.calls,
                "node B ran the in-flight step again, then the next one");
        assertEquals(List.of(StandDownReason.LEASE_FENCED), observerA.standDownReasons(WORKFLOW_ID));
        assertTrue(observerA.failed().isEmpty(), "a fence is never a workflow failure");

        var stored = store.getInstance(WORKFLOW_ID).orElseThrow();
        assertEquals(1, stored.leaseEpoch(), "exactly one adoption");
        assertEquals("x@B@B", stored.output().stringValue(), "the workflow's output is node B's");
        var activityEvents = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED)
                .toList();
        assertEquals(List.of(1, 2), activityEvents.stream().map(e -> e.sequenceNumber()).toList(),
                "one outcome per sequence");
        assertEquals("x@B", activityEvents.getFirst().payload().stringValue(),
                "sequence 1 holds node B's result; node A's was fenced");

        assertEquals(List.of(INSTANCE_KEY, INSTANCE_KEY), lock.acquiredKeys,
                "the only keys acquired are the two nodes' instance-lock keys (3.10)");
        assertTrue(lock.touchedKeys.stream().allMatch(INSTANCE_KEY::equals),
                "no lock call for an activity key: " + lock.touchedKeys);
    }

    private WorkflowExecutor node(RecordingEngineObserver observer) {
        return WorkflowExecutor.builder(store, serializer, "svc")
                .distributedLock(lock)
                .lockKeyPrefix("maestro:lock:")
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .observer(observer)
                .build();
    }

    private RaceActivities proxy(RaceActivities impl) {
        return new ActivityProxyFactory().createProxy(RaceActivities.class, impl, store, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
    }

    /** Two activities; every result is stamped with the node that produced it. */
    public interface RaceActivities {
        /** @param input the workflow input @return the input stamped with this node */
        String first(String input);

        /** @param input the first result @return the input stamped with this node */
        String second(String input);
    }

    /** Records each call; node A's first call blocks until the gate opens. */
    static final class StampedActivities implements RaceActivities {
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final String node;
        private final @Nullable CountDownLatch entered;
        private final @Nullable CountDownLatch gate;

        StampedActivities(String node, @Nullable CountDownLatch entered, @Nullable CountDownLatch gate) {
            this.node = node;
            this.entered = entered;
            this.gate = gate;
        }

        @Override
        public String first(String input) {
            calls.add("first:" + input);
            if (entered != null && gate != null) {
                entered.countDown();
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate not opened within 10 s");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            return input + "@" + node;
        }

        @Override
        public String second(String input) {
            calls.add("second:" + input);
            return input + "@" + node;
        }
    }

    /** Runs the two activities in order. */
    @DurableWorkflow(name = "RaceWorkflow")
    public static class RaceWorkflow {
        private final RaceActivities activities;

        RaceWorkflow(RaceActivities activities) {
            this.activities = activities;
        }

        /** @param input the input @return the second activity's result */
        @WorkflowMethod
        public String run(String input) {
            return activities.second(activities.first(input));
        }
    }

    /**
     * A map-backed lock shared by both nodes that records every key it is
     * asked about, through any method.
     */
    static final class KeyRecordingLock implements DistributedLock {
        final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();
        final List<String> acquiredKeys = new CopyOnWriteArrayList<>();
        final List<String> touchedKeys = new CopyOnWriteArrayList<>();

        /** Removes a key's row, as its TTL expiring would. */
        void expire(String key) {
            locks.remove(key);
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            touchedKeys.add(key);
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            if (locks.putIfAbsent(key, handle) != null) {
                return Optional.empty();
            }
            acquiredKeys.add(key);
            return Optional.of(handle);
        }

        @Override
        public void release(LockHandle handle) {
            touchedKeys.add(handle.key());
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            touchedKeys.add(handle.key());
            var current = locks.get(handle.key());
            return current != null && current.token().equals(handle.token());
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            touchedKeys.addAll(keys);
            return Set.of();
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
