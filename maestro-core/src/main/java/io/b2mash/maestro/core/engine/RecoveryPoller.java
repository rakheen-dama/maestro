package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically re-runs workflow recovery so that instances owned by a node
 * that has since died (or shut down) are picked up without a restart.
 *
 * <p>Startup recovery alone is not enough once per-instance distributed
 * locks exist: during a rolling deploy, node B's startup recovery skips
 * workflows still locked by node A — when A later releases them (or its
 * lock TTL expires after a crash), only a periodic retry can adopt them.
 *
 * <p><b>No leader election:</b> every node polls. Safety comes from the
 * instance lock (cross-node), the executor's in-JVM running-workflow check,
 * and idempotent replay; all-node polling is what makes orphan pickup fast.
 *
 * <h2>Admission</h2>
 * <p>Every pass is gated (Issue 30): before it locks an instance it asks the
 * task-queue governor for a slot without blocking, so this node locks only
 * what it can run now or queue — its free slots plus, per queue, at most the
 * queue's workflow concurrency of recovered runs waiting in the same
 * first-in-first-out line as fresh starts, where they never starve. Every
 * other instance is deferred: left unlocked, for a peer or for this node once
 * capacity frees.
 *
 * <h2>Cadence</h2>
 * <p>The first pass runs as soon as the poller starts. A <em>full</em> pass,
 * from the first row, then runs every {@code pollInterval}. After a pass that
 * deferred, the poller waits on the governor's {@link CapacitySignal} — read
 * before the pass, so a release during it is not missed — and runs a
 * <em>capacity pass</em> as soon as a slot or a recovery ticket frees, at most
 * once per {@code capacitySpacing} (by default {@code min(1 s, pollInterval)}).
 * A capacity pass resumes at the first row the previous pass deferred and
 * reads only what the free capacity can adopt; a wake with no free capacity
 * makes no statement and goes back to waiting. A pass that deferred nothing
 * waits for the next full pass.
 *
 * <h2>Supervision</h2>
 * <p>A {@link LoopBody} run under the executor's
 * {@link io.b2mash.maestro.core.loop.LoopSupervisor} as loop
 * {@value #LOOP_NAME} ({@link #spec()}). An iteration is one pass; the waits
 * between passes are its {@link Next#await custom wait}. A stop never
 * interrupts the thread (Issue 64): the spec's stop hook notifies the prompt
 * monitor and signals the capacity signal, and both waits re-check the stop
 * flag after waking. Both wakes are sticky, as {@link io.b2mash.maestro.core.loop.LoopWait}
 * requires: the capacity wait watches the signal's epoch read <em>before</em>
 * the pass, so a signal (a release, a prompt, a stop) any time after that read
 * ends it; the prompt wait re-checks the stop flag and the pending prompt
 * under the monitor before every wait.
 *
 * <p><b>Failures:</b> a pass that throws — a store fault, or any
 * {@code Error} but a {@code MaestroControlFlowError} — is a failed iteration
 * the supervisor counts and logs at its cadence; its cursor is discarded and
 * the next attempt is a full pass, one {@code pollInterval} later (60 s by
 * default).
 *
 * <p><b>Thread safety:</b> the supervisor runs {@link #iterate} and the wait
 * on the loop's thread only; {@link #requestPromptPass()} and
 * {@link #registers(String)} are safe from any thread.
 *
 * @see WorkflowExecutor#recoverWorkflows(Map)
 */
final class RecoveryPoller implements LoopBody {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryPoller.class);

    /** The loop's fixed name: its meter tag and log name. */
    static final String LOOP_NAME = "recovery-poller";

    private final WorkflowExecutor executor;
    private final Map<String, WorkflowRegistration> registrations;
    private final String serviceName;
    private final Duration pollInterval;
    private final CapacitySignal signal;
    private final Duration capacitySpacing;
    /** Guards {@link #promptRequestedAt}; notified by a prompt request and by a stop. */
    private final Object promptMonitor = new Object();
    /** {@link System#nanoTime()} of the pending prompt request (L3), or {@code null} for none. */
    private @Nullable Long promptRequestedAt;

    // Loop-thread state, between one pass and the wait after it.
    private boolean first = true;
    private long nextFull = System.nanoTime(); // pass first
    private long lastPassStart = nextFull;
    private boolean awaitingCapacity;
    private long seen;
    /** Per deferred queue, where its capacity pass resumes (a null value: the first row). */
    private Map<String, @Nullable RecoveryCursor> cursors = new HashMap<>();
    /** Set by the wait: the next pass is a capacity pass. */
    private boolean capacityPassNext;

    /**
     * Test seam: how many times the loop has checked for headroom after a
     * capacity wake. Package-private; read only by tests.
     */
    final AtomicInteger headroomChecks = new AtomicInteger();

    RecoveryPoller(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration pollInterval
    ) {
        this(executor, registrations, serviceName, pollInterval,
                executor.taskQueueGovernor().capacitySignal(), defaultCapacitySpacing(pollInterval));
    }

    /**
     * Creates a recovery poller with an explicit capacity signal and spacing.
     *
     * @param executor        the executor whose gated passes this runs
     * @param registrations   workflow type → registration, as for {@link WorkflowExecutor#recoverWorkflows(Map)}
     * @param serviceName     the owning service name (thread name)
     * @param pollInterval    interval between full passes; must be positive
     * @param signal          the executor's governor's capacity signal
     * @param capacitySpacing the least time between the starts of two passes
     *                        when the second is a capacity pass; must be positive
     */
    RecoveryPoller(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration pollInterval,
            CapacitySignal signal,
            Duration capacitySpacing
    ) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        if (capacitySpacing == null || capacitySpacing.isNegative() || capacitySpacing.isZero()) {
            throw new IllegalArgumentException("capacitySpacing must be positive, got " + capacitySpacing);
        }
        this.executor = executor;
        this.registrations = Map.copyOf(registrations);
        this.serviceName = serviceName;
        this.pollInterval = pollInterval;
        this.signal = signal;
        this.capacitySpacing = capacitySpacing;
    }

    /**
     * How the poller is supervised: critical; the first pass at once; the
     * waits between passes are the body's own (a failed pass waits
     * {@code pollInterval}); failing after two failed passes in a row
     * spanning at least {@code max(120 s, 2 × pollInterval)}; a stop wakes
     * both waits.
     *
     * @return the poller's loop spec
     */
    LoopSpec spec() {
        var twoPeriods = pollInterval.multipliedBy(2);
        var twoMinutes = Duration.ofSeconds(120);
        return LoopSpec.of(LOOP_NAME, "maestro-recovery-poller-" + serviceName, LoopRole.CRITICAL, pollInterval)
                .withFailing(FailingRule.streakFor(2, twoPeriods.compareTo(twoMinutes) > 0 ? twoPeriods : twoMinutes))
                .withOnStop(this::wakeForStop);
    }

    /** The default spacing of capacity passes, {@code min(1 s, pollInterval)}: at most one per second. */
    static Duration defaultCapacitySpacing(@Nullable Duration pollInterval) {
        var oneSecond = Duration.ofSeconds(1);
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            return oneSecond; // the constructor rejects the interval itself
        }
        return pollInterval.compareTo(oneSecond) < 0 ? pollInterval : oneSecond;
    }

    /**
     * Asks for a prompt full pass (L3): a fresh start was persisted without a
     * run because its instance lock could not be taken, and should not wait a
     * whole {@code pollInterval}. The pass runs no sooner than
     * {@code capacitySpacing} (≤ 1 s) after this request — by then the race
     * that refused the lock (a duplicate start on another node, a pool blip)
     * is usually over — and earlier full-pass timing is otherwise unchanged.
     * Requests that arrive while one is pending coalesce into it. Never
     * blocks; safe from any thread. While the poller is waiting for task-queue
     * capacity after a deferring pass, the prompt pass runs once that wait ends.
     * A prompt that arrives while a failed pass waits out its back-off is
     * served by the pass that follows the back-off.
     */
    void requestPromptPass() {
        synchronized (promptMonitor) {
            if (promptRequestedAt == null) {
                promptRequestedAt = System.nanoTime();
            }
            promptMonitor.notifyAll();
        }
        // Also ends a wait for task-queue capacity (PR #72 review): that wait
        // watches only the capacity signal. A spurious signal costs one
        // headroom check; the wait then sees the prompt and ends.
        signal.signal();
    }

    /** Whether a prompt request is pending (L3). */
    private boolean promptPending() {
        synchronized (promptMonitor) {
            return promptRequestedAt != null;
        }
    }

    /** The stop hook: wakes both waits; each re-checks the stop flag. Never interrupts. */
    private void wakeForStop() {
        synchronized (promptMonitor) {
            promptMonitor.notifyAll();
        }
        signal.signal();
    }

    /**
     * Whether this poller registers {@code workflowType}: a deferred start of
     * a type it does not register would be skipped by every pass (PR #72
     * review). Whether the poller runs is the supervisor's to say.
     *
     * @param workflowType the workflow type
     * @return {@code true} if registered for the type
     */
    boolean registers(String workflowType) {
        return registrations.containsKey(workflowType);
    }

    /** The settings, for the start line. */
    String describeSettings() {
        return "interval=%s, service=%s".formatted(pollInterval, serviceName);
    }

    // ── The iteration: one pass ─────────────────────────────────────────

    @Override
    public Next iterate(LoopContext ctx) {
        var pollNanos = pollInterval.toNanos();
        var capacityPass = capacityPassNext;
        capacityPassNext = false;
        try {
            // Read before the pass: a release, a prompt or a stop during it
            // moves the count, so the wait after it returns at once (no lost
            // wake-up — the sticky wake LoopWait requires).
            seen = signal.epoch();
            lastPassStart = System.nanoTime();
            WorkflowExecutor.RecoveryPass pass;
            if (capacityPass) {
                // Only the queues with room now, from the earliest of their own cursors:
                // a full queue's deferred rows are not re-read for another queue's room.
                var ready = new HashSet<String>();
                for (var queue : cursors.keySet()) {
                    if (executor.recoveryHeadroom(Set.of(queue)) > 0) {
                        ready.add(queue);
                    }
                }
                var start = earliest(cursors, ready);
                pass = executor.capacityPass(registrations, start, ready);
                cursors = afterCapacityPass(cursors, start, pass);
            } else {
                nextFull = lastPassStart + pollNanos;
                pass = executor.recoveryPass(registrations);
                cursors = new HashMap<>(pass.resumeAfterByQueue());
            }
            log(pass, capacityPass, first);
            first = false;
            awaitingCapacity = !cursors.isEmpty();
        } catch (RuntimeException | Error e) {
            // The cursor is discarded and the next attempt is a full pass,
            // one pollInterval on (the supervisor's back-off).
            first = false;
            awaitingCapacity = false;
            cursors = new HashMap<>();
            nextFull = System.nanoTime() + pollNanos;
            throw e;
        }
        return Next.await(this::awaitNextPass);
    }

    /**
     * The wait between passes: for capacity after a deferring pass, else for
     * the next full pass or a prompt.
     *
     * @return {@code false} when the loop is stopping
     */
    private boolean awaitNextPass(LoopContext ctx) throws InterruptedException {
        if (awaitingCapacity && awaitCapacity(ctx, seen, nextFull, lastPassStart, cursors.keySet())) {
            capacityPassNext = true;
            return !ctx.stopRequested();
        }
        return sleepUntilFullPassOrPrompt(ctx, nextFull);
    }

    /** The earliest cursor of {@code queues} in keyset order; {@code null} (the first row) is the earliest. */
    static @Nullable RecoveryCursor earliest(Map<String, @Nullable RecoveryCursor> cursors,
                                                     Set<String> queues) {
        RecoveryCursor min = null;
        var any = false;
        for (var queue : queues) {
            var cursor = cursors.get(queue);
            if (cursor == null) {
                return null;
            }
            if (!any || before(cursor, min)) {
                min = cursor;
                any = true;
            }
        }
        return min;
    }

    /** Whether {@code a} comes strictly before {@code b}; {@code null} is the first row. */
    private static boolean before(@Nullable RecoveryCursor a, @Nullable RecoveryCursor b) {
        if (a == null) {
            return b != null;
        }
        if (b == null) {
            return false;
        }
        var byStart = a.startedAt().compareTo(b.startedAt());
        return byStart < 0 || (byStart == 0 && RecoveryCursor.compareIds(a.id(), b.id()) < 0);
    }

    /**
     * The per-queue cursors after a capacity pass that read from {@code start}.
     * A queue whose cursor lies before {@code start} was not re-read and keeps
     * it. Any other queue takes the position before its first deferral in this
     * pass; with none, it is drained if the pass read to the end, and
     * otherwise resumes where the pass stopped reading.
     */
    static Map<String, @Nullable RecoveryCursor> afterCapacityPass(Map<String, @Nullable RecoveryCursor> cursors,
                                                                   @Nullable RecoveryCursor start,
                                                                   WorkflowExecutor.RecoveryPass pass) {
        var next = new HashMap<String, @Nullable RecoveryCursor>();
        var queues = new HashSet<>(cursors.keySet());
        queues.addAll(pass.resumeAfterByQueue().keySet());
        for (var queue : queues) {
            var old = cursors.get(queue);
            if (cursors.containsKey(queue) && before(old, start)) {
                next.put(queue, old);
            } else if (pass.resumeAfterByQueue().containsKey(queue)) {
                next.put(queue, pass.resumeAfterByQueue().get(queue));
            } else if (!pass.reachedEnd()) {
                var readTo = pass.readTo();
                next.put(queue, readTo != null && before(old, readTo) ? readTo : old);
            }
        }
        return next;
    }

    /**
     * After a pass that deferred: waits until capacity frees on one of
     * {@code queues}, but no sooner than {@code capacitySpacing} after
     * {@code lastPassStart}, and no later than the full pass due at
     * {@code nextFull}. A wake whose queues have no headroom makes no statement
     * and waits again, checking again no sooner than one spacing later.
     *
     * A pending prompt request (L3) ends the wait too: the method returns
     * {@code false} and the full-pass wait that follows runs the prompted pass
     * when it is due.
     *
     * @return {@code true} to run a capacity pass now; {@code false} when the
     *         full pass is due, a prompt is pending, or the poller is stopping
     */
    private boolean awaitCapacity(LoopContext ctx, long seen, long nextFull, long lastPassStart, Set<String> queues)
            throws InterruptedException {
        var spacingNanos = capacitySpacing.toNanos();
        var epoch = seen;
        // No check before one spacing after the pass, nor within one spacing of a check that found no room.
        var notBefore = lastPassStart + spacingNanos;
        while (!ctx.stopRequested()) {
            // A prompt is checked before every wait. One requested after this
            // check moves the epoch the wait watches, so the wait returns.
            if (promptPending()) {
                return false;
            }
            var untilFull = nextFull - System.nanoTime();
            if (untilFull <= 0 || !signal.awaitChange(epoch, Duration.ofNanos(untilFull))) {
                return false;
            }
            if (ctx.stopRequested() || promptPending()) {
                return false;
            }
            var spacingLeft = notBefore - System.nanoTime();
            if (spacingLeft > 0 && !sleepUntil(ctx, Math.min(notBefore, nextFull))) {
                return false;
            }
            if (nextFull - System.nanoTime() <= 0) {
                return false;
            }
            executor.beforeRecoveryHeadroomCheck.run();
            // Read before the prompt and headroom checks: a release or a
            // prompt after this read moves the epoch and wakes the next wait
            // (PR #72 review: a prompt checked before this read could be
            // absorbed by it and then wait out the full pass).
            epoch = signal.epoch();
            if (promptPending()) {
                return false;
            }
            headroomChecks.incrementAndGet();
            if (executor.recoveryHeadroom(queues) > 0) {
                return true;
            }
            notBefore = System.nanoTime() + spacingNanos;
        }
        return false;
    }

    /**
     * Waits until {@code deadline} ({@link System#nanoTime()}), a stop, or a
     * pending prompt request at least {@code capacitySpacing} old (L3), and
     * clears the prompt it returns for.
     *
     * @return {@code false} if the poller is stopping
     */
    private boolean sleepUntilFullPassOrPrompt(LoopContext ctx, long deadline) throws InterruptedException {
        var spacingNanos = capacitySpacing.toNanos();
        synchronized (promptMonitor) {
            // The stop flag is set before the stop hook notifies this monitor,
            // and is re-checked under it before every wait: no lost stop.
            while (!ctx.stopRequested()) {
                var now = System.nanoTime();
                var wakeAt = deadline;
                if (promptRequestedAt != null) {
                    var promptDue = promptRequestedAt + spacingNanos;
                    if (promptDue - now <= 0) {
                        promptRequestedAt = null;
                        return true;
                    }
                    if (promptDue - deadline < 0) {
                        wakeAt = promptDue;
                    }
                }
                var remaining = wakeAt - now;
                if (remaining <= 0) {
                    promptRequestedAt = null; // the full pass due now serves the prompt too
                    return true;
                }
                TimeUnit.NANOSECONDS.timedWait(promptMonitor, remaining);
            }
            return false;
        }
    }

    /**
     * Waits until {@code deadline} ({@link System#nanoTime()}) or a stop.
     *
     * @return {@code false} if the poller is stopping
     */
    private static boolean sleepUntil(LoopContext ctx, long deadline) {
        var remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            return ctx.sleep(Duration.ofNanos(remaining));
        }
        return !ctx.stopRequested();
    }

    private void log(WorkflowExecutor.RecoveryPass pass, boolean capacityPass, boolean first) {
        if (first) {
            logger.info("Maestro startup recovery pass: adopted {} workflow(s); {} deferred until a task-queue "
                    + "slot frees on this node", pass.adopted(), pass.deferred());
        } else if (capacityPass) {
            if (pass.adopted() > 0) {
                logger.debug("Recovery poller adopted {} deferred workflow(s) as capacity freed", pass.adopted());
            }
        } else if (pass.adopted() > 0) {
            logger.info("Recovery poller adopted {} orphaned workflow(s)", pass.adopted());
        }
    }
}
