package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports Maestro's health for Spring Boot Actuator's {@code /actuator/health}
 * endpoint.
 *
 * <p>Status is {@code DOWN} when either:
 * <ul>
 *   <li>the configured {@link WorkflowStore} cannot be reached — a cheap
 *       {@link WorkflowStore#getInstance(String)} lookup for a sentinel
 *       workflow ID that is never expected to exist throws; or</li>
 *   <li>a poller that is <em>enabled by configuration</em> is not actually
 *       running. The timer poller has no disable switch — it is always
 *       expected to run once the starter's {@code StartupRecoveryRunner}
 *       has started it — so it not running is always a fault (e.g. a
 *       crashed poller thread). The recovery poller is optional
 *       ({@code maestro.recovery.enabled}); when disabled, its non-running
 *       state is expected, not a fault.</li>
 * </ul>
 * Otherwise the status is {@code UP}. Either way, the details map reports:
 * <ul>
 *   <li>{@code store} — {@code "reachable"} or {@code "unreachable"}</li>
 *   <li>{@code timerPollerRunning} — whether the background timer poller is active</li>
 *   <li>{@code recoveryPollerRunning} — {@code true}/{@code false} when the recovery
 *       poller is enabled, or the string {@code "disabled"} when
 *       {@code maestro.recovery.enabled=false} — so operators can tell a
 *       deliberately disabled poller apart from a dead one</li>
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
     * Detail value reported for {@code recoveryPollerRunning} when the
     * recovery poller is turned off by configuration rather than dead.
     */
    static final String DISABLED = "disabled";

    private final WorkflowStore store;
    private final WorkflowExecutor executor;
    private final boolean recoveryPollerEnabled;

    /**
     * Creates a health indicator backed by the given store and executor.
     *
     * @param store                 the workflow store to probe for reachability
     * @param executor              the executor whose poller and running-workflow state is reported
     * @param recoveryPollerEnabled mirrors {@code maestro.recovery.enabled} — whether the
     *                              recovery poller is expected to be running at all
     */
    public MaestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor, boolean recoveryPollerEnabled) {
        this.store = store;
        this.executor = executor;
        this.recoveryPollerEnabled = recoveryPollerEnabled;
    }

    @Override
    public Health health() {
        try {
            store.getInstance(HEALTH_CHECK_WORKFLOW_ID);
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("store", "unreachable")
                    .build();
        }

        var timerPollerRunning = executor.isTimerPollerRunning();
        var recoveryPollerRunning = executor.isRecoveryPollerRunning();
        var recoveryPollerFault = recoveryPollerEnabled && !recoveryPollerRunning;

        var builder = Health.up()
                .withDetail("store", "reachable")
                .withDetail("timerPollerRunning", timerPollerRunning)
                .withDetail("recoveryPollerRunning", recoveryPollerEnabled ? recoveryPollerRunning : DISABLED)
                .withDetail("runningWorkflowCount", executor.runningCount());

        if (!timerPollerRunning || recoveryPollerFault) {
            builder.status(Status.DOWN);
        }

        return builder.build();
    }
}
