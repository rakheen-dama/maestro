package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The health semantics of pilot readiness P2 (design note §3): critical loops
 * that keep failing read {@code DOWN} — {@code UNKNOWN} while the store probe
 * itself times out — housekeeping loops that keep failing read {@code UP} and
 * are named in {@code degraded}, a renewer that holds locks and cannot renew
 * them reads {@code DOWN}, and the startup recovery's progress is shown.
 */
class MaestroHealthSemanticsTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private final PayloadSerializer serializer = new PayloadSerializer(new ObjectMapper());
    private final List<WorkflowExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(WorkflowExecutor::shutdown);
    }

    private WorkflowExecutor executor(WorkflowStore store, DistributedLock lock, Duration ttl) {
        var executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(lock)
                .instanceLockTtl(ttl).lifecycleEventsEnabled(false).build();
        executors.add(executor);
        return executor;
    }

    private MaestroHealthIndicator indicator() {
        return new MaestroHealthIndicator(new InMemoryWorkflowStore(),
                executor(new InMemoryWorkflowStore(), new InMemoryDistributedLock(), Duration.ofSeconds(30)), true,
                true);
    }

    private static LoopSnapshot running(String name, LoopRole role) {
        return new LoopSnapshot(name, role, LoopSnapshot.State.RUNNING, 0, null, NOW.minusSeconds(1),
                Duration.ofSeconds(1), null, null, false, Map.of());
    }

    private static LoopSnapshot failingFor(String name, LoopRole role, Duration age, boolean failing) {
        return new LoopSnapshot(name, role, LoopSnapshot.State.RUNNING, 2, NOW.minus(age), NOW.minusSeconds(300),
                Duration.ofSeconds(300), IllegalStateException.class.getName(), null, failing, Map.of());
    }

    private static List<LoopSnapshot> healthyLoopsBut(LoopSnapshot replaced) {
        var loops = new ArrayList<LoopSnapshot>();
        for (var name : List.of("instance-lock-renewer", "recovery-poller", "retention-sweeper", "signal-janitor",
                "timer-poller")) {
            if (!name.equals(replaced.name())) {
                loops.add(running(name, name.equals("retention-sweeper") || name.equals("signal-janitor")
                        ? LoopRole.HOUSEKEEPING : LoopRole.CRITICAL));
            }
        }
        loops.add(replaced);
        return loops;
    }

    private static MaestroHealthIndicator.Inputs inputs(String store, List<LoopSnapshot> loops) {
        return new MaestroHealthIndicator.Inputs(store, null, loops, 0, Duration.ofSeconds(30), NOW, 0);
    }

    /**
     * The timer poller's rule is two failures in a row spanning 60 s (its spec,
     * pinned by {@code TimerPollerTest}); the snapshot's {@code failing} says
     * whether it holds. A failing critical loop with a reachable store is
     * {@code DOWN}; 59 s of the same streak is not failing yet, so {@code UP}.
     */
    @Test
    void aTimerPollerFailingFor60sIsDownAndFor59sIsUp() {
        var indicator = indicator();
        var at59 = indicator.assess(inputs("reachable",
                healthyLoopsBut(failingFor("timer-poller", LoopRole.CRITICAL, Duration.ofSeconds(59), false))));
        var at60 = indicator.assess(inputs("reachable",
                healthyLoopsBut(failingFor("timer-poller", LoopRole.CRITICAL, Duration.ofSeconds(60), true))));
        assertThat(at59.getStatus()).isEqualTo(Status.UP);
        assertThat(at60.getStatus()).isEqualTo(Status.DOWN);
    }

    /** A critical loop failing while the probe also times out reads UNKNOWN: both most likely share a cause. */
    @Test
    void aFailingCriticalLoopWithATimedOutProbeIsUnknown() {
        var health = indicator().assess(inputs("timed out",
                healthyLoopsBut(failingFor("recovery-poller", LoopRole.CRITICAL, Duration.ofMinutes(5), true))));
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    /** A dead loop is DOWN however the probe went (aTimedOutProbeDoesNotHideADeadLoop, unchanged). */
    @Test
    void aDeadLoopIsDownThroughATimedOutProbe() {
        var dead = new LoopSnapshot("timer-poller", LoopRole.CRITICAL, LoopSnapshot.State.DEAD, 0, null, null, null,
                null, null, false, Map.of());
        assertThat(indicator().assess(inputs("timed out", healthyLoopsBut(dead))).getStatus()).isEqualTo(Status.DOWN);
    }

    /** A housekeeping loop that keeps failing does not take the pod out of readiness: UP, named in degraded. */
    @Test
    void aJanitorFailingTwiceIsUpAndDegraded() {
        var store = failingJanitorStore(new InMemoryWorkflowStore());
        var executor = executor(store, new InMemoryDistributedLock(), Duration.ofSeconds(30));
        executor.startSignalJanitor(Duration.ofMillis(20), Duration.ofDays(7));
        await().atMost(Duration.ofSeconds(5)).until(() -> executor.loops().stream()
                .anyMatch(l -> l.name().equals("signal-janitor") && l.failing()));

        var health = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("degraded", List.of("signal-janitor"))
                .containsEntry("signalJanitorRunning", true);
        @SuppressWarnings("unchecked")
        var loops = (Map<String, Map<String, Object>>) health.getDetails().get("loops");
        assertThat(loops.get("signal-janitor")).containsEntry("state", "running").containsEntry("failing", true)
                .containsKey("lastFailureType");
    }

    /**
     * Issue 68, part 1: a node holding locks whose renewer is not running is
     * DOWN. Since P2 task 4 the renewer cannot fail to start; it can still end
     * (here: a control-flow Error escapes its pass).
     */
    @Test
    void aNodeHoldingLocksWithNoRunningRenewerIsDown() throws Exception {
        var lock = new RenewAllLock(() -> {
            throw new ExecutorShutdownException("ends the renewer (test)");
        });
        var executor = executor(new InMemoryWorkflowStore(), lock, Duration.ofMillis(300));
        executor.startWorkflow("wf-held", "AwaitForever", "default", null, AwaitForever::new, awaitForever());
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isInstanceLockRenewerRunning());

        var health = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("instanceLockRenewerRunning", false)
                .containsEntry("instanceLocksHeld", 1);
    }

    /**
     * A renewer stuck in {@code renewAll} (a half-open connection) builds no
     * streak but stops renewing: once a TTL passes without a successful pass
     * while locks are held, every held lock has expired, and the node is DOWN.
     */
    @Test
    void aRenewerHoldingLocksWithNoRenewalForATtlIsDown() throws Exception {
        var stuck = new CountDownLatch(1);
        var lock = new RenewAllLock(() -> {
            try {
                stuck.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Set.of();
        });
        try {
            var executor = executor(new InMemoryWorkflowStore(), lock, Duration.ofMillis(300));
            executor.startWorkflow("wf-held", "AwaitForever", "default", null, AwaitForever::new, awaitForever());
            var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false);

            await().atMost(Duration.ofSeconds(5)).until(() -> indicator.health().getStatus().equals(Status.DOWN));

            var health = indicator.health();
            assertThat(health.getDetails()).containsEntry("instanceLockRenewerRunning", true)
                    .containsEntry("instanceLockRenewerConsecutiveFailures", 0);
        } finally {
            stuck.countDown();
        }
    }

    /**
     * P2b review m4: the renewer's "no renewal for a TTL" rule measures on the
     * monotonic clock. A wall clock that stepped 40 s forward moves
     * {@code lastSuccess} 40 s into the past, but the renewer renewed 5 s ago
     * by the monotonic clock: holding locks with a 30 s TTL, it is not stale.
     */
    @Test
    void aWallClockStepDoesNotMakeTheRenewerStale() {
        var renewer = new LoopSnapshot("instance-lock-renewer", LoopRole.CRITICAL, LoopSnapshot.State.RUNNING, 0,
                null, NOW.minusSeconds(40), Duration.ofSeconds(5), null, null, false, Map.of());
        var in = new MaestroHealthIndicator.Inputs("reachable", null, healthyLoopsBut(renewer), 1,
                Duration.ofSeconds(30), NOW, 1);
        assertThat(indicator().assess(in).getStatus()).isEqualTo(Status.UP);
    }

    /** Issue 73's done-when: startupRecovery reads running until the first pass completes, then complete. */
    @Test
    void startupRecoveryIsRunningThenComplete() {
        var executor = executor(new InMemoryWorkflowStore(), new InMemoryDistributedLock(), Duration.ofSeconds(30));
        var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, true, false);
        assertThat(indicator.health().getDetails()).containsEntry("startupRecovery", "running");

        executor.startRecoveryPoller(Map.of(), Duration.ofHours(1));

        await().atMost(Duration.ofSeconds(5)).until(() ->
                "complete".equals(indicator.health().getDetails().get("startupRecovery")));
    }

    // ── fixtures ─────────────────────────────────────────────────────

    /** A store whose janitor delete always fails. */
    private static WorkflowStore failingJanitorStore(InMemoryWorkflowStore delegate) {
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[] {WorkflowStore.class}, (self, method, args) -> {
                    if (method.getName().equals("deleteUnconsumedSignalsForTerminalInstancesBefore")) {
                        throw new IllegalStateException("db down (test)");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @FunctionalInterface
    interface RenewAllAnswer {
        Set<LockHandle> answer();
    }

    /** Grants locks and leadership; {@code renewAll} answers as told. */
    private static final class RenewAllLock implements DistributedLock {
        private final InMemoryDistributedLock delegate = new InMemoryDistributedLock();
        private final RenewAllAnswer answer;

        RenewAllLock(RenewAllAnswer answer) {
            this.answer = answer;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return delegate.tryAcquire(key, ttl);
        }

        @Override
        public void release(LockHandle handle) {
            delegate.release(handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return delegate.renew(handle, ttl);
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return answer.answer();
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    private static Method awaitForever() {
        try {
            return AwaitForever.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parks on a signal that never comes, for an hour. */
    public static class AwaitForever {

        /** Awaits a signal that is never sent. */
        public void run() {
            WorkflowContext.current().awaitSignal("never", String.class, Duration.ofHours(1));
        }
    }
}
