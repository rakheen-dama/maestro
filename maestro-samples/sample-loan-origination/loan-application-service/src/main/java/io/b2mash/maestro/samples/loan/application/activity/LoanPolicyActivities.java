package io.b2mash.maestro.samples.loan.application.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.application.workflow.LoanTimeoutPolicy;

/**
 * Supplies the workflow's configured wait durations.
 *
 * <p>Workflow instances are created reflectively and cannot receive Spring
 * beans, so configuration reaches the workflow as an activity result. The
 * result is memoized, which makes it deterministic on replay.
 */
@Activity
public interface LoanPolicyActivities {

    /**
     * Returns the configured timeouts. The workflow calls this once, first.
     */
    LoanTimeoutPolicy timeouts();
}
