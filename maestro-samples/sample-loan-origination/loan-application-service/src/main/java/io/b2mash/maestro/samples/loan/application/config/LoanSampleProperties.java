package io.b2mash.maestro.samples.loan.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code maestro.sample.*} timeout properties (SPEC defaults).
 *
 * @param docTimeout      wait for required/condition documents (default 10m)
 * @param signTimeout     wait for closing-package signatures (default 10m)
 * @param decisionTimeout wait for verification results and underwriting decisions (default 10m)
 * @param gateTimeout     short await at each withdrawal gate (default 1s)
 */
@ConfigurationProperties(prefix = "maestro.sample")
public record LoanSampleProperties(
        @DefaultValue("PT10M") Duration docTimeout,
        @DefaultValue("PT10M") Duration signTimeout,
        @DefaultValue("PT10M") Duration decisionTimeout,
        @DefaultValue("PT1S") Duration gateTimeout
) {}
