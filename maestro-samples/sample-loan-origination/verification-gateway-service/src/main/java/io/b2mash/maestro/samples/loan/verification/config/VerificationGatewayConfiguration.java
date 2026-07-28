package io.b2mash.maestro.samples.loan.verification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables binding of {@link VerificationGatewayProperties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VerificationGatewayProperties.class)
class VerificationGatewayConfiguration {}
