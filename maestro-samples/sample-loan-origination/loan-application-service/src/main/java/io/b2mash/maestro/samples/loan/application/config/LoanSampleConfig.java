package io.b2mash.maestro.samples.loan.application.config;

import io.b2mash.maestro.samples.loan.application.workflow.LoanTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code maestro.sample.*} and pushes the values into the
 * {@link LoanTimeouts} static holder that the workflow reads (workflow
 * instances are created reflectively, so they cannot receive Spring beans).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoanSampleProperties.class)
public class LoanSampleConfig {

    public LoanSampleConfig(LoanSampleProperties properties) {
        LoanTimeouts.configure(
                properties.docTimeout(),
                properties.signTimeout(),
                properties.decisionTimeout(),
                properties.gateTimeout());
    }
}
