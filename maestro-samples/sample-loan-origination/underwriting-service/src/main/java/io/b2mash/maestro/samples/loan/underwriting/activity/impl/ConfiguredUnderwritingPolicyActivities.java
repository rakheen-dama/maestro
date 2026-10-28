package io.b2mash.maestro.samples.loan.underwriting.activity.impl;

import io.b2mash.maestro.samples.loan.underwriting.activity.UnderwritingPolicyActivities;
import io.b2mash.maestro.samples.loan.underwriting.config.UnderwritingSampleProperties;
import io.b2mash.maestro.samples.loan.underwriting.workflow.UnderwritingTimeoutPolicy;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code maestro.sample.*} durations from
 * {@link UnderwritingSampleProperties}. Thread-safe: holds only immutable state.
 */
@Component
public final class ConfiguredUnderwritingPolicyActivities implements UnderwritingPolicyActivities {

    private final UnderwritingTimeoutPolicy policy;

    public ConfiguredUnderwritingPolicyActivities(UnderwritingSampleProperties properties) {
        this.policy = new UnderwritingTimeoutPolicy(
                properties.underwriterTimeout(), properties.seniorTimeout());
    }

    @Override
    public UnderwritingTimeoutPolicy timeouts() {
        return policy;
    }
}
