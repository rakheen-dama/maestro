package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Skeleton: the behaviour lands with the fix. */
public class MaestroLivenessHealthIndicator implements HealthIndicator {

    private final WorkflowExecutor executor;

    public MaestroLivenessHealthIndicator(WorkflowExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Health health() {
        return Health.up().build();
    }
}
