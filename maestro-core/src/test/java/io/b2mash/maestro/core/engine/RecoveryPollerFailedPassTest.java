package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery poller after a failed pass (P2b review m1, m2; PR #76 review)
 * and its failing rule (m3).
 *
 * <ul>
 *   <li>m1: a first pass that fails does not count as the startup pass — the
 *       first <em>successful</em> pass notes {@code firstPassComplete}, so
 *       health's {@code startupRecovery} reads {@code complete}; nor does a
 *       first pass that skipped rows because the lock backend was unavailable
 *       (PR #76 review 4629668439).</li>
 *   <li>m2: a prompt (a start deferred for its lock) ends the back-off after a
 *       failed pass and causes one pass, not two. A prompt-triggered retry
 *       runs no sooner than one second after the failed pass started, however
 *       short the poll interval (PR #76 review 4629668433), so prompts during
 *       an outage run at most one pass a second.</li>
 * </ul>
 *
 * <p>Pass progress is observed through the passes' start times, each bounded
 * by a timeout — never inferred from what an elapsed window happened to hold
 * (PR #76 review 4629668543).
 *
 * <p><b>Thread safety:</b> per-test fixtures; the counters are atomic and the
 * start times a copy-on-write list.
 */
class RecoveryPollerFailedPassTest {

    private final AtomicInteger passes = new AtomicInteger();
    private final List<Long> passStarts = new CopyOnWriteArrayList<>();
    private final List<WorkflowExecutor> executors = new ArrayList<>();
    private final List<RunningLoop> loops = new ArrayList<>();

    @AfterEach
    void tearDown() {
        loops.forEach(RunningLoop::stop);
        executors.forEach(WorkflowExecutor::shutdown);
    }

    /**
     * A store whose paged recovery read records its start and its number
     * (from 1), runs {@code onPass} with it, and throws when {@code fails}
     * holds for it.
     */
    private WorkflowStore scripted(IntConsumer onPass, IntPredicate fails) {
        var delegate = new InMemoryWorkflowStore();
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getRecoverableInstances") && args != null && args.length == 3) {
                        passStarts.add(System.nanoTime());
                        var call = passes.incrementAndGet();
                        onPass.accept(call);
                        if (fails.test(call)) {
                            throw new IllegalStateException("store down (scripted)");
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private WorkflowStore failing(IntPredicate fails) {
        return scripted(call -> { }, fails);
    }

    private WorkflowExecutor executor(WorkflowStore store) {
        return executor(store, new InMemoryDistributedLock());
    }

    private WorkflowExecutor executor(WorkflowStore store, DistributedLock lock) {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .build();
        executors.add(executor);
        return executor;
    }

    private RunningLoop run(RecoveryPoller body) {
        var loop = RunningLoop.start(body.spec(), body);
        loops.add(loop);
        return loop;
    }

    @Test
    void aFailedFirstPassIsNotTheStartupPassTheFirstSuccessfulOneIs() {
        var executor = executor(failing(call -> call == 1));
        var loop = run(new RecoveryPoller(executor, Map.of(), "svc", Duration.ofMillis(50)));
        await().atMost(Duration.ofSeconds(5)).until(() -> passes.get() >= 2);
        await("the first successful pass notes it").atMost(Duration.ofSeconds(5))
                .until(() -> Boolean.TRUE.equals(snapshot(loop).notes().get(RecoveryPoller.FIRST_PASS_COMPLETE)));
    }

    /**
     * PR #76 review 4629668439: a first pass that skipped a row because the
     * lock backend was unavailable has not assessed that workflow on this
     * node; the startup note waits for a first pass with no such row.
     */
    @Test
    void aFirstPassThatSkippedARowForTheLockBackendIsNotTheStartupPass() throws Exception {
        var store = failing(call -> false);
        seedRecoverable(store, "wf-skipped");
        var acquires = new AtomicInteger();
        var backend = new UnavailableFirstLock(acquires, 2);
        var executor = executor(store, backend);
        var loop = run(new RecoveryPoller(executor, Map.of("Quick", quick()), "svc", Duration.ofMillis(50)));

        await("the second pass began after the first skipped the row").atMost(Duration.ofSeconds(5))
                .until(() -> passes.get() >= 2);
        assertFalse(snapshot(loop).notes().containsKey(RecoveryPoller.FIRST_PASS_COMPLETE),
                "a pass that skipped a row for the lock backend is not the startup pass");
        await("the first pass that could take the lock notes it").atMost(Duration.ofSeconds(5))
                .until(() -> Boolean.TRUE.equals(snapshot(loop).notes().get(RecoveryPoller.FIRST_PASS_COMPLETE)));
    }

    @Test
    void aPromptEndsTheBackOffOfAFailedPassAndCausesOnePass() {
        var executor = executor(failing(call -> call == 1));
        var body = new RecoveryPoller(executor, Map.of(), "svc", Duration.ofHours(1),
                executor.taskQueueGovernor().capacitySignal(), Duration.ofMillis(200));
        run(body);
        await("the failed first pass").atMost(Duration.ofSeconds(5)).until(() -> passes.get() >= 1);

        body.requestPromptPass();

        await("the prompt pass, long before the 1 h back-off ends").atMost(Duration.ofSeconds(5))
                .until(() -> passes.get() >= 2);
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2)).until(() -> passes.get() == 2);
    }

    /**
     * Prompts during an outage (every pass fails) end the back-off, at most
     * one pass a second: three prompted passes are observed (bounded), and
     * consecutive pass starts are at least about a second apart.
     */
    @Test
    void promptsDuringAnOutageRunAtMostOnePassASecond() {
        var executor = executor(failing(call -> true));
        var body = new RecoveryPoller(executor, Map.of(), "svc", Duration.ofHours(1),
                executor.taskQueueGovernor().capacitySignal(), Duration.ofMillis(200));
        run(body);
        await("the failed first pass").atMost(Duration.ofSeconds(5)).until(() -> passes.get() >= 1);

        await("three prompted passes").atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(5))
                .until(() -> {
                    body.requestPromptPass();
                    return passes.get() >= 4;
                });
        assertSpacedAtLeast(Duration.ofMillis(900));
    }

    /**
     * PR #76 review 4629668433: with a 100 ms poll interval the capacity
     * spacing is 100 ms too; a prompt requested as each failing pass starts
     * must not let the next pass skip its back-off — prompt-triggered retries
     * have their own one-second bound, so the configured back-off (100 ms)
     * follows every failed pass.
     */
    @Test
    void aPromptDoesNotSkipTheBackOffWithAShortPollInterval() {
        var body = new AtomicReference<RecoveryPoller>();
        var store = scripted(call -> {
            var poller = body.get();
            if (poller != null) {
                poller.requestPromptPass();                       // a prompt near the pass's start
            }
            sleep(Duration.ofMillis(120));                        // the pass fails after 120 ms
        }, call -> true);
        var executor = executor(store);
        body.set(new RecoveryPoller(executor, Map.of(), "svc", Duration.ofMillis(100)));
        run(body.get());

        await("five passes").atMost(Duration.ofSeconds(10)).until(() -> passes.get() >= 5);
        // 120 ms of pass, then the 100 ms back-off: starts at least about 220 ms apart.
        assertSpacedAtLeast(Duration.ofMillis(200));
    }

    @Test
    void theFailingRuleIsTwoFailedPassesOverMax120sAndTwoIntervals() {
        var executor = executor(new InMemoryWorkflowStore());
        assertEquals(FailingRule.streakFor(2, Duration.ofSeconds(120)),
                new RecoveryPoller(executor, Map.of(), "svc", Duration.ofSeconds(60)).spec().failing());
        assertEquals(FailingRule.streakFor(2, Duration.ofSeconds(180)),
                new RecoveryPoller(executor, Map.of(), "svc", Duration.ofSeconds(90)).spec().failing());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private void assertSpacedAtLeast(Duration least) {
        var starts = List.copyOf(passStarts);
        for (var i = 1; i < starts.size(); i++) {
            var gap = Duration.ofNanos(starts.get(i) - starts.get(i - 1));
            assertTrue(gap.compareTo(least) >= 0, "pass " + (i + 1) + " started " + gap.toMillis()
                    + " ms after pass " + i + "; want at least " + least.toMillis() + " ms");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void seedRecoverable(WorkflowStore store, String workflowId) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID()).workflowId(workflowId).runId(UUID.randomUUID())
                .workflowType("Quick").taskQueue("default").status(WorkflowStatus.RUNNING)
                .serviceName("svc").eventSequence(0).startedAt(now).updatedAt(now).version(0)
                .build());
    }

    private static WorkflowRegistration quick() throws NoSuchMethodException {
        var runs = new AtomicInteger();
        return new WorkflowRegistration("Quick", "default", () -> new RecoveryPassAtShutdownTest.Quick(runs),
                RecoveryPassAtShutdownTest.Quick.class.getMethod("run"));
    }

    /** The lock backend is unavailable (it throws) for the first {@code failures} acquisitions. */
    private static final class UnavailableFirstLock implements DistributedLock {
        private final InMemoryDistributedLock delegate = new InMemoryDistributedLock();
        private final AtomicInteger acquires;
        private final int failures;

        UnavailableFirstLock(AtomicInteger acquires, int failures) {
            this.acquires = acquires;
            this.failures = failures;
        }

        @Override
        public java.util.Optional<io.b2mash.maestro.core.spi.LockHandle> tryAcquire(String key, Duration ttl) {
            if (acquires.incrementAndGet() <= failures) {
                throw new IllegalStateException("lock backend unavailable (scripted)");
            }
            return delegate.tryAcquire(key, ttl);
        }

        @Override
        public void release(io.b2mash.maestro.core.spi.LockHandle handle) {
            delegate.release(handle);
        }

        @Override
        public boolean renew(io.b2mash.maestro.core.spi.LockHandle handle, Duration ttl) {
            return delegate.renew(handle, ttl);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return delegate.trySetLeader(electionKey, candidateId, ttl);
        }
    }

    private static LoopSnapshot snapshot(RunningLoop loop) {
        return loop.supervisor().snapshot();
    }
}
