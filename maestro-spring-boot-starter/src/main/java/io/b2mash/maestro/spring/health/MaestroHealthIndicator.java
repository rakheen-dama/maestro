package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports Maestro's health for Spring Boot Actuator's {@code /actuator/health}
 * endpoint.
 *
 * <p>Status is {@code DOWN} when the configured {@link WorkflowStore} cannot
 * be reached — a cheap {@link WorkflowStore#getInstance(String)} lookup for a
 * sentinel workflow ID that is never expected to exist throws. Otherwise the
 * status is {@code UP}, with a details map reporting:
 * <ul>
 *   <li>{@code store} — {@code "reachable"} or {@code "unreachable"}</li>
 *   <li>{@code timerPollerRunning} — whether the background timer poller is active</li>
 *   <li>{@code recoveryPollerRunning} — whether the background recovery poller is active</li>
 *   <li>{@code runningWorkflowCount} — workflows currently executing on this node</li>
 * </ul>
 *
 * <p>The timer and recovery pollers are started by the Spring Boot starter's
 * {@code StartupRecoveryRunner} after application startup, and the recovery
 * poller is optional ({@code maestro.recovery.enabled}) — so
 * {@code recoveryPollerRunning: false} is not necessarily a fault; it is
 * reported as a detail rather than folded into the {@code UP}/{@code DOWN}
 * status.
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

    private final WorkflowStore store;
    private final WorkflowExecutor executor;

    /**
     * Creates a health indicator backed by the given store and executor.
     *
     * @param store    the workflow store to probe for reachability
     * @param executor the executor whose poller and running-workflow state is reported
     */
    public MaestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor) {
        this.store = store;
        this.executor = executor;
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

        return Health.up()
                .withDetail("store", "reachable")
                .withDetail("timerPollerRunning", executor.isTimerPollerRunning())
                .withDetail("recoveryPollerRunning", executor.isRecoveryPollerRunning())
                .withDetail("runningWorkflowCount", executor.runningCount())
                .build();
    }
}
