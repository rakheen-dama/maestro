package io.b2mash.maestro.samples.loan.underwriting.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingDecision;

/**
 * Messaging activities for the underwriting workflow.
 *
 * <p>Behind an interface so tests can capture published decisions in memory
 * instead of talking to Kafka (same pattern as the other samples'
 * messaging activities).
 */
@Activity
public interface DecisionMessagingActivities {

    /**
     * Publishes the round's decision to the {@code loans.underwriting.decisions}
     * topic, keyed by loan ID.
     *
     * @param decision the decision to publish
     */
    void publishDecision(UnderwritingDecision decision);
}
