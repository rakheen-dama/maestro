package io.b2mash.maestro.samples.loan.underwriting.config;

import io.b2mash.maestro.samples.loan.underwriting.workflow.UnderwritingWorkflow;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the sample configuration:
 * <ul>
 *   <li>Registers the {@link UnderwritingWorkflow} as a Spring bean so the
 *       Maestro starter's {@code WorkflowRegistrar} discovers it.</li>
 *   <li>Binds {@code maestro.sample.*}; the timeouts reach the workflow through
 *       the memoized {@code ConfiguredUnderwritingPolicyActivities}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnderwritingSampleProperties.class)
public class UnderwritingSampleConfiguration {

    @Bean
    public UnderwritingWorkflow underwritingWorkflow() {
        return new UnderwritingWorkflow();
    }
}
