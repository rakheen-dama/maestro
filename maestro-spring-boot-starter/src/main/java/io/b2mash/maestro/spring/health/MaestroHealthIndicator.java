package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reports Maestro's health for Spring Boot Actuator's {@code /actuator/health}
 * endpoint.
 *
 * <p>The status is one of three:
 * <ul>
 *   <li>{@code DOWN} when the configured {@link WorkflowStore} refused the
 *       probe — a cheap {@link WorkflowStore#getInstance(String)} lookup for a
 *       sentinel workflow ID that is never expected to exist threw
 *       ({@code store: "unreachable"}) — or when a loop that is <em>enabled by
 *       configuration</em> has been started at least once but is not
 *       currently running: a real fault, such as a dead loop thread. A dead
 *       loop is {@code DOWN} even when the probe also timed out;</li>
 *   <li>{@code UNKNOWN} when, with no such fault, the probe did not finish
 *       within {@link #STORE_PROBE_TIMEOUT} ({@code store: "timed out"}).
 *       Since 3.5a a probe that does not finish within the bound reports
 *       {@code UNKNOWN}: a busy pool is not a broken store. The probe runs on
 *       a separate virtual thread and is abandoned (best-effort interrupted)
 *       on timeout, so a store that is merely slow cannot hang this method or,
 *       by extension, the {@code /actuator/health} endpoint;</li>
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
 *       not do.</li>
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
 *   <li>{@code runningWorkflowCount} — workflows currently executing on this node</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> stateless and safe for concurrent use. Every
 * {@link #health()} call re-probes the store and re-reads the executor's
 * current poller and running-workflow state.
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
     * Runs store probes on their own virtual thread so a stuck call can be
     * abandoned without blocking the caller. Shared across all instances:
     * dispatching a virtual thread per submitted task has no pooled
     * resources to leak, so the executor is never explicitly shut down —
     * it lives for the process lifetime, same as any other static utility.
     */
    private static final ExecutorService PROBE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final boolean recoveryPollerEnabled;
    private final boolean retentionSweeperEnabled;

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
        this.store = store;
        this.executor = executor;
        this.recoveryPollerEnabled = recoveryPollerEnabled;
        this.retentionSweeperEnabled = retentionSweeperEnabled;
    }

    @Override
    public Health health() {
        var storeProbe = probeStore();
        var timer = pollerState(executor.hasTimerPollerStarted(), executor.isTimerPollerRunning(), false);
        var recovery = pollerState(executor.hasRecoveryPollerStarted(), executor.isRecoveryPollerRunning(),
                !recoveryPollerEnabled);
        var retention = pollerState(executor.hasRetentionSweeperStarted(), executor.isRetentionSweeperRunning(),
                !retentionSweeperEnabled);

        var loopFault = timer.fault() || recovery.fault() || retention.fault();
        var status = (loopFault || storeProbe.status().equals(UNREACHABLE)) ? Status.DOWN
                : storeProbe.status().equals(TIMED_OUT) ? Status.UNKNOWN
                : Status.UP;

        var builder = Health.status(status)
                .withDetail("store", storeProbe.status())
                .withDetail("timerPollerRunning", timer.detail())
                .withDetail("recoveryPollerRunning", recovery.detail())
                .withDetail("retentionSweeperRunning", retention.detail())
                .withDetail("runningWorkflowCount", executor.runningCount());

        if (storeProbe.exception() != null) {
            builder.withException(storeProbe.exception());
        }

        return builder.build();
    }

    /**
     * Computes the reported detail value and fault status for one poller.
     *
     * @param everStarted whether the poller has been started at least once
     * @param running     whether the poller is currently running
     * @param disabled    whether the poller is turned off by configuration
     */
    private static PollerState pollerState(boolean everStarted, boolean running, boolean disabled) {
        if (disabled) {
            return new PollerState(DISABLED, false);
        }
        if (!everStarted) {
            return new PollerState(STARTING, false);
        }
        return new PollerState(running, !running);
    }

    /** {@code detail} is a {@link Boolean}, or one of {@link #STARTING}/{@link #DISABLED}. */
    private record PollerState(Object detail, boolean fault) {
    }

    /**
     * Probes the store with a bounded timeout, never letting the probe
     * itself hang this method.
     */
    private StoreProbe probeStore() {
        var future = PROBE_EXECUTOR.submit(() -> store.getInstance(HEALTH_CHECK_WORKFLOW_ID));
        try {
            future.get(STORE_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new StoreProbe(REACHABLE, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new StoreProbe(TIMED_OUT, null);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            return new StoreProbe(UNREACHABLE, cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StoreProbe(UNREACHABLE, e);
        }
    }

    private record StoreProbe(String status, @Nullable Throwable exception) {
    }
}
