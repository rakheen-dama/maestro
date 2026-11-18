package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.SimpleHttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.SimpleStatusAggregator;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Direct (non-Spring-context) unit tests for {@link MaestroHealthIndicator},
 * covering the bounded store probe from Issue 8's follow-up review: a
 * degraded — merely slow, not cleanly failing — store must not be able to
 * hang the indicator, and by extension {@code /actuator/health}.
 * Since 3.5a a probe that does not finish within the bound reports {@code UNKNOWN}: a busy pool is not a broken store.
 */
@DisplayName("MaestroHealthIndicator")
class MaestroHealthIndicatorTest {

    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        var store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = WorkflowExecutor.builder(store, serializer, "health-indicator-test").distributedLock(new InMemoryDistributedLock()).build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── Task 7: the signal janitor and the instance-lock renewer ───────────

    private final PayloadSerializer serializer = new PayloadSerializer(new ObjectMapper());
    private final RenewFailsLock renewLock = new RenewFailsLock();
    private final WorkflowExecutor executorWithLock = WorkflowExecutor.builder(
                    new InMemoryWorkflowStore(), serializer, "svc")
            .distributedLock(new InMemoryDistributedLock())
            .lifecycleEventsEnabled(false)
            .build();
    private final WorkflowExecutor executorWithFailingRenewals = WorkflowExecutor.builder(
                    new InMemoryWorkflowStore(), serializer, "svc")
            .distributedLock(renewLock)
            .instanceLockTtl(Duration.ofMillis(300))                  // renews every 100 ms
            .lifecycleEventsEnabled(false)
            .build();
    private final Method awaitForever = awaitForeverMethod();

    @AfterEach
    void shutDownLockedExecutors() {
        executorWithLock.shutdown();
        executorWithFailingRenewals.shutdown();
    }

    @Test
    void aRenewerThatNeverStartedIsIdleAndNotAFault() {
        var health = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false).health();
        assertThat(health.getDetails()).containsEntry("instanceLockRenewerRunning", "idle");
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aRenewerThatStartedAndStoppedIsDown() throws Exception {
        executorWithLock.startWorkflow("wf-renew", "AwaitForever", "default", null, AwaitForever::new, awaitForever);
        await().atMost(Duration.ofSeconds(5)).until(executorWithLock::hasInstanceLockRenewerStarted);
        var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executorWithLock, false, false);
        assertThat(indicator.health().getDetails()).containsEntry("instanceLockRenewerRunning", true);
        executorWithLock.shutdown();
        var health = indicator.health();
        assertThat(health.getDetails()).containsEntry("instanceLockRenewerRunning", false);
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void theSignalJanitorIsStartingThenRunningThenAFaultOnceStopped() {
        var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false);
        assertThat(indicator.health().getDetails()).containsEntry("signalJanitorRunning", "starting");
        executor.startSignalJanitor(Duration.ofHours(1), Duration.ofDays(7));
        assertThat(indicator.health().getDetails()).containsEntry("signalJanitorRunning", true);
        executor.shutdown();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("signalJanitorRunning", false);
    }

    /**
     * Review d7: a renewer whose every pass fails is still running, but held
     * locks are not being renewed, so it is not {@code UP}; once a pass renews
     * again it is.
     */
    @Test
    void aRenewerThatKeepsFailingIsDownWhileItStillRunsAndUpOnceItRenewsAgain() throws Exception {
        executorWithFailingRenewals.startWorkflow("wf-failing-renew", "AwaitForever", "default", null,
                AwaitForever::new, awaitForever);
        assertThat(renewLock.renewCalls.await(5, TimeUnit.SECONDS))
                .as("three renewal passes ran (the first two's failures are fully handled by then)").isTrue();
        var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executorWithFailingRenewals,
                false, false);

        var failing = indicator.health();
        assertThat(failing.getDetails()).containsEntry("instanceLockRenewerRunning", true);
        assertThat((Integer) failing.getDetails().get("instanceLockRenewerConsecutiveFailures"))
                .isGreaterThanOrEqualTo(2);
        assertThat(failing.getStatus()).isEqualTo(Status.DOWN);

        renewLock.failing = false;
        await("a pass that renews").atMost(Duration.ofSeconds(5))
                .until(() -> executorWithFailingRenewals.instanceLockRenewerFailureStreak() == 0);
        var recovered = indicator.health();
        assertThat(recovered.getDetails()).containsEntry("instanceLockRenewerConsecutiveFailures", 0);
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
    }

    /**
     * Fix wave e3: the renewer is {@code DOWN} at a failure streak of two or
     * more, not one — the instance-lock TTL (three renew intervals) tolerates
     * one missed pass with a pass to spare. Each renewal pass is let through
     * one at a time, so the streak the indicator reads is exact.
     */
    @Test
    void theRenewerIsUpAfterOneFailedPassAndDownAfterTwoInARow() throws Exception {
        var steppedLock = new SteppedRenewLock();
        var stepped = WorkflowExecutor.builder(new InMemoryWorkflowStore(), serializer, "svc")
                .distributedLock(steppedLock)
                .instanceLockTtl(Duration.ofMillis(300))              // renews every 100 ms
                .lifecycleEventsEnabled(false)
                .build();
        try {
            stepped.startWorkflow("wf-stepped", "AwaitForever", "default", null, AwaitForever::new, awaitForever);
            var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), stepped, false, false);

            steppedLock.letOnePassThrough(2);                    // pass 1 fails; pass 2 waits inside renewAll
            assertThat(stepped.instanceLockRenewerFailureStreak()).isEqualTo(1);
            var oneMissed = indicator.health();
            assertThat(oneMissed.getDetails()).containsEntry("instanceLockRenewerRunning", true)
                    .containsEntry("instanceLockRenewerConsecutiveFailures", 1);
            assertThat(oneMissed.getStatus()).as("one failed pass: the TTL still covers the held locks")
                    .isEqualTo(Status.UP);

            steppedLock.letOnePassThrough(3);                    // pass 2 fails; pass 3 waits
            assertThat(stepped.instanceLockRenewerFailureStreak()).isEqualTo(2);
            var twoMissed = indicator.health();
            assertThat(twoMissed.getDetails()).containsEntry("instanceLockRenewerConsecutiveFailures", 2);
            assertThat(twoMissed.getStatus()).as("two failed passes in a row").isEqualTo(Status.DOWN);

            // Pass 3 succeeds. The stepping held the 300 ms lease past its TTL, so the
            // backend may report the lock lost and the renewer then has nothing left
            // to renew: wait for the streak to reset, not for a fourth renewAll.
            steppedLock.failing = false;
            steppedLock.permits.release();
            await("a pass that does not fail").atMost(Duration.ofSeconds(5))
                    .until(() -> stepped.instanceLockRenewerFailureStreak() == 0);
            var recovered = indicator.health();
            assertThat(recovered.getDetails()).containsEntry("instanceLockRenewerConsecutiveFailures", 0);
            assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        } finally {
            stepped.shutdown();
        }
    }

    /**
     * A lock whose {@code renewAll} waits for {@link #letOnePassThrough} before
     * each call goes on, then throws while {@link #failing}. Interruptible, so
     * the executor's shutdown ends a waiting pass.
     */
    private static final class SteppedRenewLock implements DistributedLock {

        private final InMemoryDistributedLock delegate = new InMemoryDistributedLock();
        private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(0);
        private final AtomicInteger entered = new AtomicInteger();
        volatile boolean failing = true;

        /**
         * Lets the pass now waiting inside {@code renewAll} go on, and returns
         * once pass {@code next} has entered it — so the earlier pass's outcome
         * has been fully handled by the renewer.
         */
        void letOnePassThrough(int next) {
            await("pass " + (next - 1) + " to enter renewAll").atMost(Duration.ofSeconds(5))
                    .until(() -> entered.get() >= next - 1);
            permits.release();
            await("pass " + next + " to enter renewAll").atMost(Duration.ofSeconds(5))
                    .until(() -> entered.get() >= next);
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
            entered.incrementAndGet();
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for a permit", e);
            }
            if (failing) {
                throw new IllegalStateException("db down");
            }
            return delegate.renewAll(handles, ttl);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return delegate.trySetLeader(electionKey, candidateId, ttl);
        }
    }

    private static Method awaitForeverMethod() {
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

    /**
     * {@link InMemoryDistributedLock} whose {@code renewAll} throws while
     * {@link #failing}, as a lock backend whose database is down does.
     */
    private static final class RenewFailsLock implements DistributedLock {

        private final InMemoryDistributedLock delegate = new InMemoryDistributedLock();
        final CountDownLatch renewCalls = new CountDownLatch(3);
        volatile boolean failing = true;

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
            renewCalls.countDown();
            if (failing) {
                throw new IllegalStateException("db down");
            }
            return delegate.renewAll(handles, ttl);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return delegate.trySetLeader(electionKey, candidateId, ttl);
        }
    }

    @Test
    @DisplayName("returns promptly, bounded by STORE_PROBE_TIMEOUT, when the store call blocks indefinitely, and reports UNKNOWN")
    void returnsPromptlyWhenStoreBlocksIndefinitely() {
        var indicator = new MaestroHealthIndicator(new BlockingWorkflowStore(), executor, true, true);

        var start = Instant.now();
        var health = indicator.health();
        var elapsed = Duration.between(start, Instant.now());

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("store", "timed out");
        assertThat(elapsed)
                .as("must not hang on a store call that never returns — bounded by "
                        + MaestroHealthIndicator.STORE_PROBE_TIMEOUT + " plus scheduling slack, "
                        + "not indefinitely")
                .isLessThan(MaestroHealthIndicator.STORE_PROBE_TIMEOUT.plusSeconds(5));
    }

    @Test
    void aStoreThatThrowsWithinTheBoundIsDown() {
        var health = new MaestroHealthIndicator(new ThrowingWorkflowStore(), executor, true, true).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("store", "unreachable");
    }

    @Test
    void aTimedOutProbeDoesNotHideADeadLoop() {
        executor.startTimerPoller(Duration.ofSeconds(5), 100);
        executor.shutdown();                                   // started, then stopped: a fault
        var health = new MaestroHealthIndicator(new BlockingWorkflowStore(), executor, false, false).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("store", "timed out").containsEntry("timerPollerRunning", false);
    }

    /**
     * Pins the Spring Boot defaults that Operations §14 states: an {@code UNKNOWN}
     * {@code maestro} next to an {@code UP} member leaves the aggregate {@code UP},
     * and {@code UNKNOWN} itself answers 200 — only {@code DOWN} and
     * {@code OUT_OF_SERVICE} answer 503.
     */
    @Test
    void bootsDefaultsLeaveAnUnknownMaestroAt200() {
        assertThat(StatusAggregator.getDefault().getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN)))
                .isEqualTo(Status.UP);
        assertThat(HttpCodeStatusMapper.DEFAULT.getStatusCode(Status.UNKNOWN)).isEqualTo(200);
        assertThat(HttpCodeStatusMapper.DEFAULT.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(HttpCodeStatusMapper.DEFAULT.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }

    /**
     * Pins what Operations §14 tells a host that wants a 503: map {@code unknown}
     * ({@code management.endpoint.health.status.http-mapping.unknown=503}), and,
     * for an aggregate or group that has an {@code UP} member too, order
     * {@code UNKNOWN} above {@code UP}
     * ({@code management.endpoint.health.status.order=DOWN,OUT_OF_SERVICE,UNKNOWN,UP}).
     */
    @Test
    void aHostCanMapUnknownTo503() {
        assertThat(new SimpleHttpCodeStatusMapper(Map.of("unknown", 503)).getStatusCode(Status.UNKNOWN))
                .isEqualTo(503);
        assertThat(new SimpleStatusAggregator("DOWN", "OUT_OF_SERVICE", "UNKNOWN", "UP")
                .getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN)))
                .isEqualTo(Status.UNKNOWN);
    }

    /**
     * A probe stuck in a store call that ignores the interrupt must not be
     * joined by a second stuck probe on every later health call: repeated calls
     * share the one in flight, each still bounded, and once it returns the next
     * call probes afresh.
     */
    @Test
    void aStuckProbeIsSharedByLaterCallsNotStartedAgain() throws Exception {
        var store = new UninterruptibleBlockingStore();
        var indicator = new MaestroHealthIndicator(store, executor, false, false);
        try {
            for (int call = 1; call <= 3; call++) {
                var start = Instant.now();
                var health = indicator.health();
                assertThat(Duration.between(start, Instant.now()))
                        .as("call %d is bounded by the probe timeout", call)
                        .isLessThan(MaestroHealthIndicator.STORE_PROBE_TIMEOUT.plusSeconds(5));
                assertThat(health.getStatus()).as("call %d", call).isEqualTo(Status.UNKNOWN);
                assertThat(health.getDetails()).containsEntry("store", "timed out");
            }
            assertThat(store.inside.get())
                    .as("store calls still blocked after three health calls (one probe thread each)")
                    .isEqualTo(1);
        } finally {
            store.release.countDown();
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> store.inside.get() == 0);
        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("store", "reachable");
        assertThat(store.entered.get()).as("a fresh probe once the stuck one returned").isEqualTo(2);
    }

    /**
     * A probe that timed out is still interrupted (best effort), so a store
     * call that honours the interrupt frees its thread. Issue 66 records the
     * cost of that interrupt mid-query.
     */
    @Test
    void aTimedOutProbeIsInterrupted() throws Exception {
        var interrupted = new CountDownLatch(1);
        var store = new DelegatingWorkflowStore() {
            @Override
            public Optional<WorkflowInstance> getInstance(String workflowId) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
                return Optional.empty();
            }
        };
        var health = new MaestroHealthIndicator(store, executor, false, false).health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the timed-out probe was interrupted").isTrue();
    }

    /**
     * A caller that joined a probe another caller has already abandoned — and
     * interrupted — must not report the store as unreachable because the
     * store call then failed on that interrupt.
     */
    @Test
    void aCallerSharingAnAbandonedProbeReportsTimedOutNotUnreachable() throws Exception {
        var entered = new CountDownLatch(1);
        var store = new DelegatingWorkflowStore() {
            @Override
            public Optional<WorkflowInstance> getInstance(String workflowId) {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("store call interrupted", e);
                }
                return Optional.empty();
            }
        };
        var indicator = new MaestroHealthIndicator(store, executor, false, false);
        var first = CompletableFuture.supplyAsync(indicator::health, Executors.newVirtualThreadPerTaskExecutor());
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("the first call's probe reached the store").isTrue();
        var second = indicator.health();
        assertThat(first.get(10, TimeUnit.SECONDS).getDetails()).containsEntry("store", "timed out");
        assertThat(second.getDetails()).containsEntry("store", "timed out");
        assertThat(second.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    // ── Fix wave e1: the single-flight probe's edge cases ──────────────────

    /**
     * A call that finds the probe in flight already abandoned by an earlier
     * call — which waited the full bound for it — answers {@code "timed out"}
     * at once instead of waiting its own bound for the same stuck probe.
     */
    @Test
    void aCallThatFindsAnAbandonedProbeAnswersTimedOutAtOnce() throws Exception {
        var store = new UninterruptibleBlockingStore();
        var indicator = new MaestroHealthIndicator(store, executor, false, false);
        try {
            assertThat(indicator.health().getDetails()).as("the first call waits the bound")
                    .containsEntry("store", "timed out");

            var start = Instant.now();
            var second = indicator.health();
            var elapsed = Duration.between(start, Instant.now());

            assertThat(second.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(second.getDetails()).containsEntry("store", "timed out");
            assertThat(elapsed).as("a call that joins an abandoned probe does not wait the bound again")
                    .isLessThan(Duration.ofMillis(500));
            assertThat(store.entered.get()).as("still the one stuck probe").isEqualTo(1);
        } finally {
            store.release.countDown();
        }
    }

    /**
     * If the probe thread cannot be started, the call fails with that
     * throwable and the in-flight slot is cleared, so the next call probes
     * afresh instead of joining a probe that never ran (which would read
     * {@code UNKNOWN} for ever).
     */
    @Test
    void aProbeThreadThatFailsToStartDoesNotLeaveTheIndicatorStuck() {
        var built = new AtomicInteger();
        ThreadFactory threads = task -> built.getAndIncrement() == 0
                ? new Thread(task) {
                    @Override
                    public void start() {
                        throw new OutOfMemoryError("unable to create thread (injected)");
                    }
                }
                : Thread.ofVirtual().name(MaestroHealthIndicator.PROBE_THREAD_NAME).unstarted(task);
        var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false, threads);

        var failed = indicator.health();
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails()).containsEntry("store", "unreachable");

        var start = Instant.now();
        var health = indicator.health();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(500));
        assertThat(health.getDetails()).containsEntry("store", "reachable");
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    /**
     * A control-flow {@code Error} from the store call still completes the
     * probe, so the caller (and any call that joined it) learns at once that
     * the probe failed instead of waiting the bound and reading
     * {@code UNKNOWN}.
     */
    @Test
    void aControlFlowErrorInTheProbeCompletesItAtOnce() {
        var store = new DelegatingWorkflowStore() {
            @Override
            public Optional<WorkflowInstance> getInstance(String workflowId) {
                throw new ExecutorShutdownException("injected in the probe");
            }
        };
        var indicator = new MaestroHealthIndicator(store, executor, false, false);

        var start = Instant.now();
        var health = indicator.health();
        var elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed).as("the caller does not wait the bound for a probe that already ended")
                .isLessThan(Duration.ofMillis(500));
        assertThat(health.getDetails()).containsEntry("store", "unreachable");
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * Blocks in {@link #getInstance(String)} until {@link #release} opens,
     * swallowing every interrupt — a store call that a timed-out probe's
     * interrupt cannot free.
     */
    private static final class UninterruptibleBlockingStore extends DelegatingWorkflowStore {

        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger entered = new AtomicInteger();

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            entered.incrementAndGet();
            inside.incrementAndGet();
            try {
                while (true) {
                    try {
                        release.await();
                        return Optional.empty();
                    } catch (InterruptedException ignored) {
                        // swallowed on purpose
                    }
                }
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    /** Refuses the probe at once, as a store whose database rejects connections does. */
    private static final class ThrowingWorkflowStore extends DelegatingWorkflowStore {

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new IllegalStateException("connection refused");
        }
    }

    /** Blocks forever in {@link #getInstance(String)} to simulate a hung (degraded, not failed) store. */
    private static final class BlockingWorkflowStore extends DelegatingWorkflowStore {

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            try {
                new CountDownLatch(1).await(); // never counts down — blocks until interrupted
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
