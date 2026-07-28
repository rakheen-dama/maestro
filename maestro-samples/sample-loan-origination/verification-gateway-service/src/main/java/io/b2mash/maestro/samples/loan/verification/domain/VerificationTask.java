package io.b2mash.maestro.samples.loan.verification.domain;

/**
 * Workflow input for {@code VerificationWorkflow}.
 *
 * <p>The simulated provider latency is resolved from configuration
 * ({@code maestro.sample.verification.latency.*}) by the Kafka listener
 * <em>before</em> the workflow starts and carried in the input. Because the
 * workflow input is persisted, the latency seen by the workflow is identical
 * on every replay even if the configuration changes while the workflow is
 * in flight — keeping the workflow code fully deterministic.
 *
 * @param loanId                 the loan application id
 * @param type                   the verification type: {@code credit},
 *                               {@code employment} or {@code appraisal}
 * @param amount                 the requested loan amount
 * @param simulatedLatencyMillis simulated provider latency in milliseconds,
 *                               slept via {@code workflow.sleep()} (a durable
 *                               timer, not {@code Thread.sleep()})
 */
public record VerificationTask(String loanId, String type, long amount, long simulatedLatencyMillis) {}
