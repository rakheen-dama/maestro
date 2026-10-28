package io.b2mash.maestro.samples.loan.underwriting.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.underwriting.workflow.UnderwritingTimeoutPolicy;

/**
 * Supplies the workflow's configured human-decision timeouts.
 *
 * <p>Workflow instances cannot receive Spring beans, so configuration reaches
 * the workflow as an activity result; being memoized, it is deterministic on replay.
 */
@Activity
public interface UnderwritingPolicyActivities {

    /**
     * Returns the configured timeouts. The workflow calls this once, first.
     */
    UnderwritingTimeoutPolicy timeouts();
}
