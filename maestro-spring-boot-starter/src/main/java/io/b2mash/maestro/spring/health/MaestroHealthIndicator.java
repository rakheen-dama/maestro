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
 * <p>Status is {@code DOWN} when either:
 * <ul>
 *   <li>the configured {@link WorkflowStore} cannot be reached within
 *       {@link #STORE_PROBE_TIMEOUT} — a cheap
 *       {@link WorkflowStore#getInstance(String)} lookup for a sentinel
 *       workflow ID that is never expected to exist either throws or does
 *       not complete in time. The probe runs on a separate virtual thread
 *       and is abandoned (best-effort interrupted) on timeout, so a store
 *       that is merely slow — not cleanly unreachable — cannot hang this
 *       method or, by extension, the {@code /actuator/health} endpoint; or</li>
 *   <li>a poller that is <em>enabled by configuration</em> has been started
 *       at least once but is not currently running — a real fault, such as
 *       a crashed poller thread.</li>
 * </ul>
 * A poller that has never been started yet is <b>not</b> treated as a fault:
 * the timer and recovery pollers are only started by the Spring Boot
 * starter's {@code StartupRecoveryRunner}, an {@code ApplicationRunner}
 * that runs after the web server (and therefore the actuator endpoint) is
 * already accepting requests. Folding "hasn't started yet" into
 * {@code DOWN} would report every normal boot — and every rolling
 * deploy — as unhealthy during that window, indistinguishable from an
 * actual crash. Instead such a poller is reported as {@code "starting"}
 * and does not affect status.
 *
 * <p>Otherwise the status is {@code UP}. In every case the details map
 * reports:
 * <ul>
 *   <li>{@code store} — {@code "reachable"}, {@code "unreachable"}, or {@code "timed out"}</li>
 *   <li>{@code timerPollerRunning} — {@code true}/{@code false} once the timer poller has
 *       started at least once, or the string {@code "starting"} before then</li>
 *   <li>{@code recoveryPollerRunning} — {@code true}/{@code false} once the recovery poller
 *       (when enabled) has started at least once, the string {@code "starting"} before
 *       then, or the string {@code "disabled"} when {@code maestro.recovery.enabled=false}
 *       — so operators can tell "off on purpose", "still starting", and "dead" apart</li>
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

    /**
     * Creates a health indicator backed by the given store and executor.
     *
     * @param store                 the workflow store to probe for reachability
     * @param executor              the executor whose poller and running-workflow state is reported
     * @param recoveryPollerEnabled mirrors {@code maestro.recovery.enabled} — whether the
     *                              recovery poller is expected to run at all
     */
    public MaestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor, boolean recoveryPollerEnabled) {
        this.store = store;
        this.executor = executor;
        this.recoveryPollerEnabled = recoveryPollerEnabled;
    }

    @Override
    public Health health() {
        var storeProbe = probeStore();
        var timer = pollerState(executor.hasTimerPollerStarted(), executor.isTimerPollerRunning(), false);
        var recovery = pollerState(executor.hasRecoveryPollerStarted(), executor.isRecoveryPollerRunning(),
                !recoveryPollerEnabled);

        var status = (storeProbe.status().equals("reachable") && !timer.fault() && !recovery.fault())
                ? Status.UP
                : Status.DOWN;

        var builder = Health.status(status)
                .withDetail("store", storeProbe.status())
                .withDetail("timerPollerRunning", timer.detail())
                .withDetail("recoveryPollerRunning", recovery.detail())
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
            return new StoreProbe("reachable", null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new StoreProbe("timed out", null);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            return new StoreProbe("unreachable", cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StoreProbe("unreachable", e);
        }
    }

    private record StoreProbe(String status, @Nullable Throwable exception) {
    }
}
