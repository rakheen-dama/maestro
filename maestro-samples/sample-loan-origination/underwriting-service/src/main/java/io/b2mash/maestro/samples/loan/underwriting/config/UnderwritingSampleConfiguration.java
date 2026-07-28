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
 *   <li>Applies the {@code maestro.sample.*} timeouts to the workflow before
 *       any workflow can start (bean creation happens before Kafka listeners
 *       and the web layer come up).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnderwritingSampleProperties.class)
public class UnderwritingSampleConfiguration {

    public UnderwritingSampleConfiguration(UnderwritingSampleProperties properties) {
        UnderwritingWorkflow.configureTimeouts(
                properties.underwriterTimeout(), properties.seniorTimeout());
    }

    @Bean
    public UnderwritingWorkflow underwritingWorkflow() {
        return new UnderwritingWorkflow();
    }
}
