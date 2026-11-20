package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports Maestro's health for Spring Boot Actuator's {@code /actuator/health}
 * endpoint, from a bounded store probe and one snapshot of every supervised
 * loop ({@code WorkflowExecutor.loops()}; pilot readiness P2, design note §3).
 *
 * <h2>Status, in precedence order</h2>
 * <ol>
 *   <li>{@code DOWN} when the store refused the probe
 *       ({@code store: "unreachable"}); when any loop is {@code DEAD} (started,
 *       then ended while not stopping) or, enabled and started, is no longer
 *       running (a stopped loop; a finished one-shot pass is not a fault); when
 *       the instance-lock renewer fails two passes in a row, or holds locks and
 *       has not renewed for a whole lock TTL; or when a <em>critical</em> loop
 *       (timer poller, recovery pollers, renewer) is failing — its failure
 *       streak has reached its rule, at least two failures spanning at least a
 *       minute or two periods — while the probe answered;</li>
 *   <li>{@code UNKNOWN} when the probe did not finish within
 *       {@link #STORE_PROBE_TIMEOUT} ({@code store: "timed out"}) and nothing
 *       above holds. A critical loop failing while the probe also times out
 *       reads {@code UNKNOWN}, not {@code DOWN}: both most likely share one
 *       cause, a busy pool (3.5a). A dead loop still reads {@code DOWN};</li>
 *   <li>{@code UP} otherwise — a failing <em>housekeeping</em> or
 *       <em>optimisation</em> loop (signal janitor, retention sweeper, lock
 *       cleaner, wake listener) does not take the pod out of readiness; it is
 *       named in {@code degraded}, and {@code maestro.loop.failing{loop}} reads
 *       {@code 1}.</li>
 * </ol>
 * The probe runs on a separate virtual thread and is abandoned on timeout, so
 * a store that is merely slow cannot hang this method or the endpoint. An
 * abandoned probe is <b>never interrupted</b> (Issue 66): on a virtual thread
 * inside a JDBC call an interrupt closes the connection, which the pool then
 * evicts; the store call is left to finish and its result is ignored. One
 * probe is in flight per indicator: a call that finds one still running joins
 * it; a call that finds it already timed out answers {@code "timed out"} at
 * once — until {@link #SUPERSEDE_AFTER} has passed since it was abandoned
 * (its first caller's timeout), when the next call starts one fresh probe
 * beside it, so one probe stuck for good (a
 * half-open socket and no driver socket timeout) does not hold
 * {@code UNKNOWN} once the store answers again. A probe is superseded at most
 * once while it is stuck, so at most two probes — two connections — exist at
 * a time; the price is that if the fresh probe is stuck for good too, the
 * status stays {@code UNKNOWN} until the fresh (in-flight) probe returns — or,
 * if the superseded one returns first, until {@link #SUPERSEDE_AFTER} has
 * passed since the fresh one's own abandonment and it is superseded (a driver socket
 * timeout makes sure a stuck call does return). If the
 * probe thread cannot be started, the call reports {@code DOWN} with that
 * failure.
 *
 * <p>A loop that has never been started yet is <b>not</b> a fault: the timer
 * and recovery pollers are started by the starter's {@code StartupRecoveryRunner},
 * after the web server accepts requests, so such a loop reads
 * {@code "starting"} and does not affect status.
 *
 * <h2>Readiness; liveness is {@code maestroLiveness}</h2>
 * <p>This indicator is a readiness signal at most (Operations §14): add it to
 * {@code readiness} only if the pod's HTTP traffic needs Maestro's store
 * ({@code readinessState,maestro}), give that probe {@code timeoutSeconds: 3}
 * or more, and never put it in {@code liveness} — a broken database is not
 * fixed by a restart. {@link MaestroLivenessHealthIndicator}
 * ({@code maestroLiveness}) is the liveness contributor: no I/O, {@code DOWN}
 * only for a dead loop. Spring Boot's default status order is
 * {@code DOWN, OUT_OF_SERVICE, UP, UNKNOWN}, so an {@code UNKNOWN}
 * {@code maestro} next to an {@code UP} member leaves the aggregate
 * {@code UP}, and {@code UNKNOWN} answers HTTP 200.
 *
 * <h2>Details</h2>
 * <ul>
 *   <li>{@code store} — {@code "reachable"}, {@code "unreachable"} or {@code "timed out"}</li>
 *   <li>{@code timerPollerRunning}, {@code recoveryPollerRunning},
 *       {@code retentionSweeperRunning}, {@code signalJanitorRunning} —
 *       {@code true}/{@code false} once started, {@code "starting"} before,
 *       {@code "disabled"} when turned off by configuration (recovery poller,
 *       retention sweeper)</li>
 *   <li>{@code instanceLockRenewerRunning} — {@code true}/{@code false} (the
 *       renewer starts with the executor; the former {@code "idle"} is gone)
 *       and {@code instanceLockRenewerConsecutiveFailures}</li>
 *   <li>{@code lockCleanerRunning}, {@code wakeListenerRunning} and
 *       {@code wakeListenerConnected} — when {@code maestro-store-postgres}
 *       supervises those loops</li>
 *   <li>{@code instanceLocksHeld}, {@code runningWorkflowCount}</li>
 *   <li>{@code startupRecovery} — {@code "running"} until the recovery poller's
 *       first pass, or the one-shot startup pass, has completed, then
 *       {@code "complete"} (Issue 73; information only)</li>
 *   <li>{@code degraded} — the failing non-critical loops</li>
 *   <li>{@code loops} — one entry per loop: {@code state}, {@code failing},
 *       {@code consecutiveFailures}, and when known {@code lastSuccess},
 *       {@code failingSince}, {@code lastFailureType}, {@code inIterationForMs}
 *       and the loop's notes (such as {@code leader})</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> safe for concurrent use. The only state is the
 * reference to the probe in flight and to the one superseded probe, each
 * swapped atomically (lock-free); no call blocks beyond
 * {@link #STORE_PROBE_TIMEOUT}, and no call starts a probe thread while one
 * is in flight, except the one supersession. Every {@link #health()} call re-reads the
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

    /**
     * How long after its abandonment — the first call's {@link #STORE_PROBE_TIMEOUT}
     * running out, so {@code STORE_PROBE_TIMEOUT} after the probe started — a
     * probe still stuck is superseded by the next call with one fresh probe:
     * five times {@link #STORE_PROBE_TIMEOUT}, so a probe is superseded no
     * sooner than 12 s after it started (Issue 66).
     */
    static final Duration SUPERSEDE_AFTER = STORE_PROBE_TIMEOUT.multipliedBy(5);

    /** Name of the virtual thread a store probe runs on. */
    static final String PROBE_THREAD_NAME = "maestro-health-probe";

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final boolean recoveryPollerEnabled;
    private final boolean retentionSweeperEnabled;
    private final ThreadFactory probeThreads;
    private final LongSupplier nanoClock;

    /**
     * The probe whose store call has not returned yet, if any. At most one
     * probe thread per indicator exists at a time: a call that finds one in
     * flight waits on it (within the same bound; a probe an earlier call already
     * timed out is answered {@code "timed out"} at once) instead of starting another,
     * so a stuck store call cannot pile up one stuck thread per health call.
     * Cleared by the probe thread when its store call returns or throws.
     */
    private final AtomicReference<@Nullable Probe> inFlight = new AtomicReference<>();

    /**
     * The probe a fresh one superseded, while its store call is still stuck.
     * Non-null blocks a second supersession, so at most two probes exist.
     * Cleared by that probe's thread when its store call returns or throws.
     */
    private final AtomicReference<@Nullable Probe> superseded = new AtomicReference<>();

    /**
     * Creates a health indicator backed by the given store and executor.
     *
     * @param store                   the workflow store to probe for reachability
     * @param executor                the executor whose poller and running-workflow state is reported
     * @param recoveryPollerEnabled   mirrors {@code maestro.testing.recovery.enabled} (testing only) — whether the
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
        this(store, executor, recoveryPollerEnabled, retentionSweeperEnabled, probeThreads, System::nanoTime);
    }

    /**
     * Test seam: {@code nanoClock} is the monotonic clock that times how long a
     * timed-out probe has been stuck.
     */
    MaestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor, boolean recoveryPollerEnabled,
                           boolean retentionSweeperEnabled, ThreadFactory probeThreads, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
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
                executor.instanceLocksHeld(), executor.instanceLockTtl(), executor.oldestInstanceLockRenewalAge(), executor.runningCount()));
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
     * @param oldestLockRenewalAge {@code WorkflowExecutor.oldestInstanceLockRenewalAge()}
     * @param runningCount   workflows running here
     */
    record Inputs(String storeStatus, @Nullable Throwable storeException, List<LoopSnapshot> loops, int locksHeld,
                  Duration lockTtl, @Nullable Duration oldestLockRenewalAge, int runningCount) {
    }

    /** The status and details for {@code in}; see the class Javadoc. */
    Health assess(Inputs in) {
        var byName = new TreeMap<String, LoopSnapshot>();
        in.loops().forEach(loop -> byName.put(loop.name(), loop));
        var timer = pollerState(byName.get(TIMER_POLLER), false);
        var recovery = pollerState(byName.get(RECOVERY_POLLER), !recoveryPollerEnabled);
        var retention = pollerState(byName.get(RETENTION_SWEEPER), !retentionSweeperEnabled);
        var janitor = pollerState(byName.get(SIGNAL_JANITOR), false);
        var renewerSnapshot = byName.get(RENEWER);
        var renewerFailures = renewerSnapshot == null ? 0 : renewerSnapshot.consecutiveFailures();
        var renewer = renewerState(renewerSnapshot, in);

        // Step 1 of the precedence (design note §3): DOWN regardless of the probe.
        var down = in.storeStatus().equals(UNREACHABLE) || renewer.fault();
        var criticalFailing = false;
        var degraded = new ArrayList<String>();
        for (var loop : byName.values()) {
            if (loop.state() == LoopSnapshot.State.DEAD) {
                down = true;                          // ended while not stopping
            } else if (!loop.running() && loop.state() != LoopSnapshot.State.DONE) {
                down = true;                          // started, and no longer running (as before)
            }
            if (loop.failing()) {
                if (loop.role() == LoopRole.CRITICAL) {
                    criticalFailing = true;
                } else {
                    degraded.add(loop.name());
                }
            }
        }
        var probeReachable = in.storeStatus().equals(REACHABLE);
        var status = down || (criticalFailing && probeReachable) ? Status.DOWN
                : in.storeStatus().equals(TIMED_OUT) ? Status.UNKNOWN
                : Status.UP;

        var builder = Health.status(status)
                .withDetail("store", in.storeStatus())
                .withDetail("timerPollerRunning", timer.detail())
                .withDetail("recoveryPollerRunning", recovery.detail())
                .withDetail("retentionSweeperRunning", retention.detail())
                .withDetail("signalJanitorRunning", janitor.detail())
                .withDetail("instanceLockRenewerRunning", renewer.detail())
                .withDetail("instanceLockRenewerConsecutiveFailures", renewerFailures);
        var cleaner = byName.get(LOCK_CLEANER);
        if (cleaner != null) {
            builder.withDetail("lockCleanerRunning", cleaner.running());
        }
        var wake = byName.get(WAKE_LISTENER);
        if (wake != null) {
            builder.withDetail("wakeListenerRunning", wake.running())
                    // Deliberately not "…Running": a disconnected listener is
                    // degraded, not a dead loop (perf/k6/health-probe.js#loopFault).
                    .withDetail("wakeListenerConnected", Boolean.TRUE.equals(wake.notes().get(CONNECTED)));
        }
        builder.withDetail("instanceLocksHeld", in.locksHeld())
                .withDetail("runningWorkflowCount", in.runningCount())
                .withDetail("startupRecovery", startupRecovery(byName))
                .withDetail("degraded", List.copyOf(degraded))
                .withDetail("loops", loopDetails(byName));

        if (in.storeException() != null) {
            builder.withException(in.storeException());
        }

        return builder.build();
    }

    /** {@code "complete"} once the recovery poller's first pass or the one-shot pass completed (Issue 73). */
    private static String startupRecovery(Map<String, LoopSnapshot> byName) {
        var oneShot = byName.get(STARTUP_RECOVERY);
        if (oneShot != null && oneShot.state() == LoopSnapshot.State.DONE) {
            return COMPLETE;
        }
        var poller = byName.get(RECOVERY_POLLER);
        if (poller != null && Boolean.TRUE.equals(poller.notes().get(FIRST_PASS_COMPLETE))) {
            return COMPLETE;
        }
        return RUNNING;
    }

    /** One small map per loop, in name order. */
    private static Map<String, Map<String, Object>> loopDetails(Map<String, LoopSnapshot> byName) {
        var loops = new LinkedHashMap<String, Map<String, Object>>();
        for (var loop : byName.values()) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("state", loop.state().name().toLowerCase(Locale.ROOT));
            detail.put("failing", loop.failing());
            detail.put("consecutiveFailures", loop.consecutiveFailures());
            if (loop.lastSuccess() != null) {
                detail.put("lastSuccess", loop.lastSuccess().toString());
            }
            if (loop.failingSince() != null) {
                detail.put("failingSince", loop.failingSince().toString());
            }
            if (loop.lastFailureType() != null) {
                detail.put("lastFailureType", loop.lastFailureType());
            }
            if (loop.inIterationFor() != null) {
                detail.put("inIterationForMs", loop.inIterationFor().toMillis());
            }
            detail.putAll(loop.notes());
            loops.put(loop.name(), detail);
        }
        return loops;
    }

    static final String TIMER_POLLER = "timer-poller";
    static final String RECOVERY_POLLER = "recovery-poller";
    static final String STARTUP_RECOVERY = "startup-recovery";
    static final String SIGNAL_JANITOR = "signal-janitor";
    static final String RETENTION_SWEEPER = "retention-sweeper";
    static final String RENEWER = "instance-lock-renewer";
    static final String LOCK_CLEANER = "lock-cleaner";
    static final String WAKE_LISTENER = "wake-listener";
    /** The recovery poller's note once its first pass completed. */
    static final String FIRST_PASS_COMPLETE = "firstPassComplete";
    /** The wake listener's note: whether its LISTEN session is open. */
    static final String CONNECTED = "connected";
    /** {@code startupRecovery} detail values. */
    static final String RUNNING = "running";
    static final String COMPLETE = "complete";

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
     * The instance-lock renewer's detail and fault (design note §3). A fault,
     * {@code DOWN} whatever the probe said, when:
     * <ul>
     *   <li>its last {@link #RENEWER_FAULT_STREAK} passes in a row failed
     *       (review d7, fix wave e3): it runs, but held locks are not renewed.
     *       One failed pass is not: the TTL tolerates it;</li>
     *   <li>locks are held and no pass has succeeded for a whole TTL — a
     *       renewer stuck in {@code renewAll} builds no streak, but by then every
     *       held lock has expired;</li>
     *   <li>it is not running (it starts with the executor, so this is a
     *       renewer that ended, or a node shutting down).</li>
     * </ul>
     */
    private static PollerState renewerState(@Nullable LoopSnapshot renewer, Inputs in) {
        if (renewer == null) {
            return new PollerState(false, true);
        }
        var running = renewer.running();
        // The held lock closest to expiry, since its last renewal or its
        // acquisition (PR #76 review): a failed attempt resets nothing, and a
        // node holding no lock is never stale.
        var age = in.oldestLockRenewalAge();
        var stale = age != null && age.compareTo(in.lockTtl()) >= 0;
        return new PollerState(running,
                !running || renewer.consecutiveFailures() >= RENEWER_FAULT_STREAK || stale);
    }

    /** {@code detail} is a {@link Boolean}, or one of {@link #STARTING}/{@link #DISABLED}. */
    private record PollerState(Object detail, boolean fault) {
    }

    /**
     * Probes the store with a bounded timeout, never letting the probe
     * itself hang this method. Joins the probe already in flight, if any; on
     * timeout the probe is abandoned — never interrupted — and later calls
     * answer "timed out" at once until its store call returns or, after
     * {@link #SUPERSEDE_AFTER}, one fresh probe supersedes it.
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
            if (current != null && !supersedes(current)) {
                return current;
            }
            var fresh = new Probe();
            if (inFlight.compareAndSet(current, fresh)) {
                try {
                    // The thread is created here, inside the handler, not with the
                    // probe: a factory that throws is a probe that never ran, like a
                    // thread that cannot start (PR #78 review 4634059487). A probe this
                    // one superseded stays in the superseded slot — it is still stuck
                    // and still counts against the two-probe bound.
                    probeThreads.newThread(fresh::run).start();
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
     * Whether {@code current} — the probe in flight — is to be superseded by a
     * fresh one, and if so claims the one superseded slot for it. Only a probe
     * abandoned for at least {@link #SUPERSEDE_AFTER} whose store call has not
     * returned, and only while no earlier superseded probe is still stuck.
     */
    private boolean supersedes(Probe current) {
        // abandoned is written after abandonedAtNanos: read it first.
        if (!current.abandoned || current.result.isDone()
                || nanoClock.getAsLong() - current.abandonedAtNanos < SUPERSEDE_AFTER.toNanos()) {
            return false;
        }
        if (!superseded.compareAndSet(null, current)) {
            return false; // one superseded probe is still stuck: never a third
        }
        if (current.result.isDone() || inFlight.get() != current) {
            // It returned, or another call replaced it, while this one decided.
            superseded.compareAndSet(current, null);
            return false;
        }
        return true;
    }

    /**
     * One store call on its own virtual thread. Thread-safe: {@code result}
     * and {@code abandoned} are read by every caller that joined it.
     */
    private final class Probe {

        final CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        volatile boolean abandoned;
        /** {@link #nanoClock} when first abandoned; read only once {@link #abandoned} is set. */
        volatile long abandonedAtNanos;

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

        /**
         * Clears this probe's slot first — {@link #inFlight}, or the superseded
         * slot — before completing the result. When this was the in-flight
         * probe, a call after the result is known starts a fresh probe; when it
         * was the superseded one, the in-flight probe stays and is joined (or,
         * once it is old enough, superseded) as before.
         */
        private void finish(@Nullable Throwable failure) {
            inFlight.compareAndSet(this, null);
            superseded.compareAndSet(this, null);
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
            }
        }

        /**
         * Marks the probe timed out — never interrupts it (Issue 66). The first
         * abandonment's time is kept: callers joined to the same probe that
         * time out after it do not restart the supersede clock.
         */
        void abandon() {
            synchronized (this) {
                if (abandoned) {
                    return;
                }
                abandonedAtNanos = nanoClock.getAsLong();
                abandoned = true;
            }
        }
    }

    private record StoreProbe(String status, @Nullable Throwable exception) {
    }
}
