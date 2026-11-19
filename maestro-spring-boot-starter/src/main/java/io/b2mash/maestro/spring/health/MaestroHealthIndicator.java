package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports Maestro's health for Spring Boot Actuator's {@code /actuator/health}
 * endpoint.
 *
 * <p>The status is one of three:
 * <ul>
 *   <li>{@code DOWN} when the configured {@link WorkflowStore} refused the
 *       probe — a cheap {@link WorkflowStore#getInstance(String)} lookup for a
 *       sentinel workflow ID that is never expected to exist threw, a
 *       control-flow {@code Error} included ({@code store: "unreachable"}) —
 *       or when a loop that is <em>enabled by
 *       configuration</em> has been started at least once but is not
 *       currently running: a real fault, such as a dead loop thread — or when
 *       the instance-lock renewer, though running, has failed two passes in a
 *       row. A loop fault is {@code DOWN} even when the probe also timed
 *       out;</li>
 *   <li>{@code UNKNOWN} when, with no such fault, the probe did not finish
 *       within {@link #STORE_PROBE_TIMEOUT} ({@code store: "timed out"}).
 *       Since 3.5a a probe that does not finish within the bound reports
 *       {@code UNKNOWN}: a busy pool is not a broken store. The probe runs on
 *       a separate virtual thread and is abandoned (best-effort interrupted)
 *       on timeout, so a store that is merely slow cannot hang this method or,
 *       by extension, the {@code /actuator/health} endpoint. At most one probe
 *       is in flight per indicator: a call that finds one still running joins
 *       it, so a store call that ignores the interrupt holds one thread, not
 *       one per health call. A call that finds the probe already timed out by
 *       an earlier call answers {@code "timed out"} at once rather than
 *       waiting the bound again. If the probe thread cannot be started, the
 *       call reports {@code DOWN} with that failure and the next call probes
 *       afresh;</li>
 *   <li>{@code UP} otherwise.</li>
 * </ul>
 * A loop that has never been started yet is <b>not</b> treated as a fault:
 * the timer and recovery pollers are only started by the Spring Boot
 * starter's {@code StartupRecoveryRunner}, an {@code ApplicationRunner}
 * that runs after the web server (and therefore the actuator endpoint) is
 * already accepting requests. Folding "hasn't started yet" into
 * {@code DOWN} would report every normal boot — and every rolling
 * deploy — as unhealthy during that window, indistinguishable from an
 * actual crash. Instead such a poller is reported as {@code "starting"}
 * and does not affect status.
 *
 * <h2>Readiness, never liveness</h2>
 * <p>This indicator is a readiness signal at most (Operations §14):
 * <ul>
 *   <li>never add {@code maestro} to the {@code liveness} group, and never
 *       point a liveness probe at the aggregate {@code /actuator/health} —
 *       restarting a busy node abandons its in-flight runs to the lock TTL;</li>
 *   <li>add it to {@code readiness} only if the pod's HTTP traffic needs
 *       Maestro's store
 *       ({@code management.endpoint.health.group.readiness.include=readinessState,maestro});</li>
 *   <li>a dead loop reads {@code DOWN} and its thread's end is logged at
 *       {@code ERROR}; the remedy is restarting the node, which readiness does
 *       not do;</li>
 *   <li>{@link #health()} can take up to {@link #STORE_PROBE_TIMEOUT} (2 s) to
 *       answer, longer than a kubelet probe's default {@code timeoutSeconds}
 *       of 1: give a readiness probe that includes {@code maestro}
 *       {@code timeoutSeconds: 3} or more, or a slow store fails the HTTP
 *       probe itself and the {@code UNKNOWN} (HTTP 200) answer never reaches
 *       the kubelet.</li>
 * </ul>
 * Spring Boot's default status order is {@code DOWN, OUT_OF_SERVICE, UP,
 * UNKNOWN}, so an {@code UNKNOWN} {@code maestro} leaves an aggregate that has
 * an {@code UP} member {@code UP}, and {@code UNKNOWN} answers HTTP 200.
 *
 * <p>In every case the details map reports:
 * <ul>
 *   <li>{@code store} — {@code "reachable"}, {@code "unreachable"}, or {@code "timed out"}</li>
 *   <li>{@code timerPollerRunning} — {@code true}/{@code false} once the timer poller has
 *       started at least once, or the string {@code "starting"} before then</li>
 *   <li>{@code recoveryPollerRunning} — {@code true}/{@code false} once the recovery poller
 *       (when enabled) has started at least once, the string {@code "starting"} before
 *       then, or the string {@code "disabled"} when {@code maestro.recovery.enabled=false}
 *       — so operators can tell "off on purpose", "still starting", and "dead" apart</li>
 *   <li>{@code retentionSweeperRunning} — the same three-way value for the retention
 *       sweeper, {@code "disabled"} when {@code maestro.retention.enabled=false}. Note
 *       that "running" means the sweep loop is alive on this node, not that this node
 *       is the elected leader that actually purges</li>
 *   <li>{@code signalJanitorRunning} — {@code true}/{@code false} once the signal janitor has
 *       started, or the string {@code "starting"} before then</li>
 *   <li>{@code instanceLockRenewerRunning} — {@code true}/{@code false} once this node's
 *       instance-lock renewer has started, or the string {@code "idle"} before this node takes
 *       its first instance lock (a {@code DistributedLock} is mandatory, so only a node that has
 *       run no workflow yet stays {@code "idle"}); not a fault</li>
 *   <li>{@code instanceLockRenewerConsecutiveFailures} — the renewer's consecutive failed passes
 *       since its last good one; at {@code 2} or more the status is {@code DOWN}, even with the
 *       renewer running, because held locks are not being renewed. A single failed pass reads
 *       {@code UP} with the streak shown: the TTL (three renew intervals) tolerates it</li>
 *   <li>{@code runningWorkflowCount} — workflows currently executing on this node</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> safe for concurrent use. The only state is the
 * reference to the probe in flight, swapped atomically (lock-free); no call
 * blocks beyond {@link #STORE_PROBE_TIMEOUT} and no call starts a second probe
 * thread while one is in flight. Every {@link #health()} call re-reads the
 * executor's current loop and running-workflow state, and re-probes the store
 * unless a probe is still in flight, which it joins.
 *
 * @see MaestroHealthAutoConfiguration
 */
public class MaestroHealthIndicator implements HealthIndicator {

    /**
     * Sentinel workflow ID used only to probe store reachability; never
     * expected to exist.
     */
    static final String HEALTH_CHECK_WORKFLOW_ID = "__maestro-health-check__";

    /**
     * Detail value reported for a poller that is turned off by
     * configuration rather than dead.
     */
    static final String DISABLED = "disabled";

    /**
     * Detail value reported for a poller that has not been started yet —
     * expected during the startup window, before the starter's
     * {@code StartupRecoveryRunner} has run.
     */
    static final String STARTING = "starting";

    /**
     * Detail value for the instance-lock renewer before this node has acquired its first instance lock: a node that
     * has run no workflow yet (a {@code DistributedLock} is mandatory). Not a fault.
     */
    static final String IDLE = "idle";

    /** {@code store} detail: the probe returned within the bound. */
    static final String REACHABLE = "reachable";

    /** {@code store} detail: the probe threw within the bound. Makes the status {@code DOWN}. */
    static final String UNREACHABLE = "unreachable";

    /**
     * {@code store} detail: the probe did not finish within
     * {@link #STORE_PROBE_TIMEOUT}. Makes the status {@code UNKNOWN} unless a
     * loop fault makes it {@code DOWN}.
     */
    static final String TIMED_OUT = "timed out";

    /**
     * Upper bound on how long the store reachability probe may take. A
     * degraded (slow, not cleanly failing) store must not be able to hang
     * this indicator, and by extension {@code /actuator/health}, for
     * longer than this. Not currently exposed as a configuration property —
     * the probe is a single cheap point lookup, so a short, fixed bound is
     * appropriate for every deployment.
     */
    static final Duration STORE_PROBE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * The instance-lock renewer's failure streak at which a running renewer is
     * a fault: two failed passes in a row. The TTL (30 s by default) is three
     * renew intervals, so it covers roughly two missed passes: after one, a
     * pass is still to spare; after two, held locks are at the edge of
     * expiring.
     */
    static final int RENEWER_FAULT_STREAK = 2;

    /** Name of the virtual thread a store probe runs on. */
    static final String PROBE_THREAD_NAME = "maestro-health-probe";

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final boolean recoveryPollerEnabled;
    private final boolean retentionSweeperEnabled;
    private final ThreadFactory probeThreads;

    /**
     * The probe whose store call has not returned yet, if any. At most one
     * probe thread per indicator exists at a time: a call that finds one in
     * flight waits on it (within the same bound; a probe an earlier call already
     * timed out is answered {@code "timed out"} at once) instead of starting another,
     * so a store call that ignores the interrupt cannot pile up one stuck
     * thread per health call. Cleared by the probe thread when its store call
     * returns or throws.
     */
    private final AtomicReference<@Nullable Probe> inFlight = new AtomicReference<>();

    /**
     * Creates a health indicator backed by the given store and executor.
     *
     * @param store                   the workflow store to probe for reachability
     * @param executor                the executor whose poller and running-workflow state is reported
     * @param recoveryPollerEnabled   mirrors {@code maestro.recovery.enabled} — whether the
     *                                recovery poller is expected to run at all
     * @param retentionSweeperEnabled mirrors {@code maestro.retention.enabled} — whether the
     *                                retention sweeper is expected to run at all
     */
    public MaestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor, boolean recoveryPollerEnabled,
                                  boolean retentionSweeperEnabled) {
        this(store, executor, recoveryPollerEnabled, retentionSweeperEnabled,
                Thread.ofVirtual().name(PROBE_THREAD_NAME).factory());
    }

    /**
     * Test seam: {@code probeThreads} builds (unstarted) the thread each probe
     * runs on, instead of a named virtual thread.
     */
    MaestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor, boolean recoveryPollerEnabled,
                           boolean retentionSweeperEnabled, ThreadFactory probeThreads) {
        this.store = store;
        this.executor = executor;
        this.recoveryPollerEnabled = recoveryPollerEnabled;
        this.retentionSweeperEnabled = retentionSweeperEnabled;
        this.probeThreads = probeThreads;
    }

    @Override
    public Health health() {
        var storeProbe = probeStore();
        return assess(new Inputs(storeProbe.status(), storeProbe.exception(), executor.loops(),
                executor.instanceLocksHeld(), executor.instanceLockTtl(), Instant.now(), executor.runningCount()));
    }

    /**
     * What {@link #health()} reads, gathered once: the store probe's outcome
     * and one snapshot of every loop. A test seam: {@link #assess} is a pure
     * function of it.
     *
     * @param storeStatus    {@link #REACHABLE}, {@link #UNREACHABLE} or {@link #TIMED_OUT}
     * @param storeException what the probe threw, if it did
     * @param loops          {@code WorkflowExecutor.loops()}
     * @param locksHeld      instance locks this node holds
     * @param lockTtl        the instance lock TTL
     * @param now            the wall clock, for the age of a last success
     * @param runningCount   workflows running here
     */
    record Inputs(String storeStatus, @Nullable Throwable storeException, List<LoopSnapshot> loops, int locksHeld,
                  Duration lockTtl, Instant now, int runningCount) {
    }

    /** The status and details for {@code in}; see the class Javadoc. */
    Health assess(Inputs in) {
        var byName = new HashMap<String, LoopSnapshot>();
        in.loops().forEach(loop -> byName.put(loop.name(), loop));
        var timer = pollerState(byName.get(TIMER_POLLER), false);
        var recovery = pollerState(byName.get(RECOVERY_POLLER), !recoveryPollerEnabled);
        var retention = pollerState(byName.get(RETENTION_SWEEPER), !retentionSweeperEnabled);
        var janitor = pollerState(byName.get(SIGNAL_JANITOR), false);
        var renewerSnapshot = byName.get(RENEWER);
        var renewerFailures = renewerSnapshot == null ? 0 : renewerSnapshot.consecutiveFailures();
        var renewer = renewerState(renewerSnapshot);

        var loopFault = timer.fault() || recovery.fault() || retention.fault() || janitor.fault() || renewer.fault();
        var status = (loopFault || in.storeStatus().equals(UNREACHABLE)) ? Status.DOWN
                : in.storeStatus().equals(TIMED_OUT) ? Status.UNKNOWN
                : Status.UP;

        var builder = Health.status(status)
                .withDetail("store", in.storeStatus())
                .withDetail("timerPollerRunning", timer.detail())
                .withDetail("recoveryPollerRunning", recovery.detail())
                .withDetail("retentionSweeperRunning", retention.detail())
                .withDetail("signalJanitorRunning", janitor.detail())
                .withDetail("instanceLockRenewerRunning", renewer.detail())
                .withDetail("instanceLockRenewerConsecutiveFailures", renewerFailures)
                .withDetail("runningWorkflowCount", in.runningCount());

        if (in.storeException() != null) {
            builder.withException(in.storeException());
        }

        return builder.build();
    }

    static final String TIMER_POLLER = "timer-poller";
    static final String RECOVERY_POLLER = "recovery-poller";
    static final String STARTUP_RECOVERY = "startup-recovery";
    static final String SIGNAL_JANITOR = "signal-janitor";
    static final String RETENTION_SWEEPER = "retention-sweeper";
    static final String RENEWER = "instance-lock-renewer";

    /**
     * Computes the reported detail value and fault status for one poller.
     *
     * @param loop     the loop's snapshot, or {@code null} if it was never started
     * @param disabled whether the poller is turned off by configuration
     */
    private static PollerState pollerState(@Nullable LoopSnapshot loop, boolean disabled) {
        if (disabled) {
            return new PollerState(DISABLED, false);
        }
        if (loop == null) {
            return new PollerState(STARTING, false);
        }
        var running = loop.running();
        return new PollerState(running, !running);
    }

    /**
     * The instance-lock renewer's detail and fault. A renewer that is running
     * but whose last {@link #RENEWER_FAULT_STREAK} passes in a row failed is a
     * fault too (review d7, fix wave e3): its thread is alive, but the locks
     * it holds are not being renewed. One failed pass is not (see
     * {@link #RENEWER_FAULT_STREAK}): a single refused {@code renewAll} must
     * not take a node out of readiness.
     */
    private static PollerState renewerState(@Nullable LoopSnapshot renewer) {
        if (renewer == null) {
            return new PollerState(IDLE, false);
        }
        var running = renewer.running();
        return new PollerState(running, !running || renewer.consecutiveFailures() >= RENEWER_FAULT_STREAK);
    }

    /** {@code detail} is a {@link Boolean}, or one of {@link #STARTING}/{@link #DISABLED}/{@link #IDLE}. */
    private record PollerState(Object detail, boolean fault) {
    }

    /**
     * Probes the store with a bounded timeout, never letting the probe
     * itself hang this method. Joins the probe already in flight, if any; on
     * timeout the probe is abandoned and its thread interrupted (best effort),
     * and later calls answer "timed out" at once until its store call returns.
     */
    private StoreProbe probeStore() {
        var probe = inFlightOrNewProbe();
        if (probe.abandoned && !probe.result.isDone()) {
            // An earlier call already waited the full bound for this probe and gave
            // up on it; waiting the bound again for the same stuck call tells
            // nothing new and only slows the endpoint down.
            return new StoreProbe(TIMED_OUT, null);
        }
        try {
            probe.result.get(STORE_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new StoreProbe(REACHABLE, null);
        } catch (TimeoutException e) {
            probe.abandon();
            return new StoreProbe(TIMED_OUT, null);
        } catch (ExecutionException e) {
            if (probe.abandoned) {
                // Another call already timed this probe out and interrupted it, so its
                // failure is most likely that interrupt, not a store that refused.
                return new StoreProbe(TIMED_OUT, null);
            }
            var cause = e.getCause();
            return new StoreProbe(UNREACHABLE, cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StoreProbe(UNREACHABLE, e);
        }
    }

    private Probe inFlightOrNewProbe() {
        while (true) {
            var current = inFlight.get();
            if (current != null) {
                return current;
            }
            var fresh = new Probe();
            if (inFlight.compareAndSet(null, fresh)) {
                try {
                    fresh.thread.start();
                } catch (Throwable t) {
                    // A probe that never ran must not stay in flight: every later call
                    // would join it and read "timed out" for ever. A call that joined it
                    // meanwhile learns of the failure at once.
                    // The failure is reported as an unreachable store (DOWN), never
                    // thrown: a plain HealthIndicator that throws fails the whole
                    // health endpoint with a 500.
                    inFlight.compareAndSet(fresh, null);
                    fresh.result.completeExceptionally(t);
                    if (t instanceof MaestroControlFlowError controlFlow) {
                        throw controlFlow;
                    }
                }
                return fresh;
            }
        }
    }

    /**
     * One store call on its own virtual thread. Thread-safe: {@code result}
     * and {@code abandoned} are read by every caller that joined it.
     */
    private final class Probe {

        final CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        final Thread thread = probeThreads.newThread(this::run);
        volatile boolean abandoned;

        private void run() {
            try {
                store.getInstance(HEALTH_CHECK_WORKFLOW_ID);
                finish(null);
            } catch (Throwable t) {
                // Completed first in every case, so no caller waits the bound for a
                // probe that has already ended; a control-flow Error is then still
                // rethrown and ends this thread, as the engine's rule requires.
                finish(t);
                if (t instanceof MaestroControlFlowError controlFlow) {
                    throw controlFlow;
                }
            }
        }

        /** Clears {@link #inFlight} first, so a call after the result is known starts a fresh probe. */
        private void finish(@Nullable Throwable failure) {
            inFlight.compareAndSet(this, null);
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
            }
        }

        /** Marks the probe timed out, then interrupts it; the order lets a joined caller see the mark. */
        void abandon() {
            abandoned = true;
            thread.interrupt();
        }
    }

    private record StoreProbe(String status, @Nullable Throwable exception) {
    }
}
