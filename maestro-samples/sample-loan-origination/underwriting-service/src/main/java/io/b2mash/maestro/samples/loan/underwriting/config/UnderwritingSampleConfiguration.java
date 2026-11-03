package io.b2mash.maestro.samples.loan.underwriting.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code maestro.sample.*}; the timeouts reach the workflow through the
 * memoized {@code ConfiguredUnderwritingPolicyActivities}, not a static holder.
 *
 * <p>The {@code UnderwritingWorkflow} is deliberately <em>not</em> declared
 * here: the Maestro starter scans the application's package for
 * {@code @DurableWorkflow} classes and registers each as a prototype bean, so
 * every run gets its own workflow object. A {@code @Bean} method would make it
 * a singleton, which the starter's {@code WorkflowRegistrar} rejects at
 * startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnderwritingSampleProperties.class)
public class UnderwritingSampleConfiguration {
}
