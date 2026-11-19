package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.Objects;

/**
 * {@code maestroLiveness}: Maestro's liveness contributor (pilot readiness P2,
 * design note §3, maintainer question Q1). {@code DOWN} only for a state that
 * is permanent and that a restart fixes — a supervised loop that died: started,
 * then ended while not asked to stop (a control-flow {@code Error} escaped it).
 * Because every loop, the instance-lock renewer included, starts with the
 * executor or its starter, "locks held with no running renewer" is that same
 * case: a dead renewer.
 *
 * <p>Never {@code DOWN} for a failing loop (a broken database is not fixed by a
 * restart) and never for a stopping, stopped or finished one, so a draining pod
 * stays live. It reads loop snapshots only — no store call, no pool wait, no
 * probe thread — and answers in microseconds. Put it in the liveness group:
 * {@code management.endpoint.health.group.liveness.include=livenessState,maestroLiveness}
 * (Operations §14), with a {@code failureThreshold} of 3 or more.
 *
 * <p>Details: {@code dead}, the names of the dead loops.
 *
 * <p><b>Thread safety:</b> stateless; safe for concurrent use.
 */
public class MaestroLivenessHealthIndicator implements HealthIndicator {

    private final WorkflowExecutor executor;

    /**
     * @param executor the executor whose loops are read
     */
    public MaestroLivenessHealthIndicator(WorkflowExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Health health() {
        var dead = executor.loops().stream()
                .filter(loop -> loop.state() == LoopSnapshot.State.DEAD)
                .map(LoopSnapshot::name)
                .toList();
        return Health.status(dead.isEmpty() ? Status.UP : Status.DOWN)
                .withDetail("dead", dead)
                .build();
    }
}
