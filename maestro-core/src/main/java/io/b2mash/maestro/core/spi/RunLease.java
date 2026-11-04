package io.b2mash.maestro.core.spi;

import java.util.Objects;
import java.util.UUID;

/**
 * The lease under which one local run of a workflow writes to the store: the
 * instance it is writing and the {@code lease_epoch} the run read when it
 * adopted the instance.
 *
 * <p>Every adoption of a workflow instance — a fresh start, a recovery resume,
 * a manual retry's relaunch — bumps the instance row's
 * {@link io.b2mash.maestro.core.model.WorkflowInstance#leaseEpoch() leaseEpoch}
 * by one with a version-checked compare-and-set, and the run carries the epoch
 * it wrote. A run-scoped write ({@link WorkflowStore#appendEvent(io.b2mash.maestro.core.model.WorkflowEvent, RunLease)}
 * and its siblings) succeeds only while the stored epoch still equals the
 * lease's epoch. Once another node has adopted the instance the stored epoch is
 * higher and every write from the previous holder is <em>fenced</em>: rejected
 * with {@link io.b2mash.maestro.core.exception.FencedWriteException}, nothing
 * written. This is what turns a lost or expired instance lock (Issue 11) from
 * "the unique event index will probably catch the loser" into "the loser
 * cannot persist progress at all".
 *
 * <p>A lease is a value: two runs that read the same epoch of the same instance
 * hold equal leases, and at most one of them can have performed the adoption
 * CAS that produced it.
 *
 * @param instanceId the workflow instance's primary key
 * @param epoch      the {@code lease_epoch} this run adopted the instance at;
 *                   {@code 0} for a run that started the instance
 */
public record RunLease(UUID instanceId, long epoch) {

    /**
     * Creates a lease.
     *
     * @throws NullPointerException     if {@code instanceId} is {@code null}
     * @throws IllegalArgumentException if {@code epoch} is negative
     */
    public RunLease {
        Objects.requireNonNull(instanceId, "instanceId");
        if (epoch < 0) {
            throw new IllegalArgumentException("lease epoch must be >= 0, got " + epoch);
        }
    }
}
