package io.b2mash.maestro.samples.loan.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code maestro.sample.*}. The values reach the workflow through the
 * memoized {@code ConfiguredLoanPolicyActivities}, not a static holder.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoanSampleProperties.class)
public class LoanSampleConfig {
}
