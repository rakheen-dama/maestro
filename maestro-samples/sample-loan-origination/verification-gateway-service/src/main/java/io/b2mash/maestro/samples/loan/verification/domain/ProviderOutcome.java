package io.b2mash.maestro.samples.loan.verification.domain;

/**
 * Result of a (simulated) third-party verification provider call.
 *
 * @param approved whether the provider approved the verification
 * @param details  human-readable outcome detail
 */
public record ProviderOutcome(boolean approved, String details) {}
