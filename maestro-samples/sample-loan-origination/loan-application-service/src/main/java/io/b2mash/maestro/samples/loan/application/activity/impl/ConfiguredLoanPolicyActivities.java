package io.b2mash.maestro.samples.loan.application.activity.impl;

import io.b2mash.maestro.samples.loan.application.activity.LoanPolicyActivities;
import io.b2mash.maestro.samples.loan.application.config.LoanSampleProperties;
import io.b2mash.maestro.samples.loan.application.workflow.LoanTimeoutPolicy;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code maestro.sample.*} durations from {@link LoanSampleProperties}
 * (so a per-node override such as {@code MAESTRO_SAMPLE_DECISION_TIMEOUT} applies
 * to runs that node starts). Thread-safe: holds only immutable state.
 */
@Component
public final class ConfiguredLoanPolicyActivities implements LoanPolicyActivities {

    private final LoanTimeoutPolicy policy;

    public ConfiguredLoanPolicyActivities(LoanSampleProperties properties) {
        this.policy = new LoanTimeoutPolicy(
                properties.docTimeout(),
                properties.signTimeout(),
                properties.decisionTimeout(),
                properties.gateTimeout());
    }

    @Override
    public LoanTimeoutPolicy timeouts() {
        return policy;
    }
}
