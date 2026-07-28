package io.b2mash.maestro.samples.loan.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Sample configuration for the verification gateway.
 *
 * <p>Per-type simulated provider latency, bound from
 * {@code maestro.sample.verification.latency.*}:
 *
 * <pre>{@code
 * maestro:
 *   sample:
 *     verification:
 *       latency:
 *         credit: PT2S       # default
 *         employment: PT5S   # default
 *         appraisal: PT8S    # default
 * }</pre>
 *
 * @param latency per-type simulated provider latency
 */
@ConfigurationProperties(prefix = "maestro.sample.verification")
public record VerificationGatewayProperties(@DefaultValue Latency latency) {

    /**
     * Per-type latency values.
     *
     * @param credit     simulated credit-check latency (default 2s)
     * @param employment simulated employment-check latency (default 5s)
     * @param appraisal  simulated appraisal latency (default 8s)
     */
    public record Latency(
            @DefaultValue("PT2S") Duration credit,
            @DefaultValue("PT5S") Duration employment,
            @DefaultValue("PT8S") Duration appraisal
    ) {}

    /**
     * Resolves the simulated latency for a verification type.
     *
     * @param type {@code credit}, {@code employment} or {@code appraisal}
     * @return the configured latency
     * @throws IllegalArgumentException for an unknown type
     */
    public Duration latencyFor(String type) {
        return switch (type) {
            case "credit" -> latency.credit();
            case "employment" -> latency.employment();
            case "appraisal" -> latency.appraisal();
            default -> throw new IllegalArgumentException("Unknown verification type: " + type);
        };
    }
}
