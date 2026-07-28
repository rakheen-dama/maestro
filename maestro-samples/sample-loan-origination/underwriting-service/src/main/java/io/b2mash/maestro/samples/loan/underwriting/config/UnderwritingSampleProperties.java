package io.b2mash.maestro.samples.loan.underwriting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Sample-level timeouts, bound from {@code maestro.sample.*}.
 *
 * <p>SPEC defaults are demo-fast (10 minutes per human decision desk);
 * tests override them to sub-second values via
 * {@code UnderwritingWorkflow.configureTimeouts}.
 *
 * @param underwriterTimeout how long to wait for the underwriter's decision
 *                           before escalating to a senior underwriter
 * @param seniorTimeout      how long to wait for the senior underwriter's
 *                           decision before rejecting with "no decision"
 */
@ConfigurationProperties(prefix = "maestro.sample")
public record UnderwritingSampleProperties(
        @DefaultValue("PT10M") Duration underwriterTimeout,
        @DefaultValue("PT10M") Duration seniorTimeout
) {
}
