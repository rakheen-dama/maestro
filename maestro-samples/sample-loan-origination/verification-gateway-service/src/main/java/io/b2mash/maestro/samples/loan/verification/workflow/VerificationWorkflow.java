package io.b2mash.maestro.samples.loan.verification.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.samples.loan.verification.activity.VerificationMessagingActivities;
import io.b2mash.maestro.samples.loan.verification.activity.VerificationProviderActivities;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationResult;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationTask;

import java.time.Duration;

/**
 * Durable workflow simulating a single third-party verification
 * (credit, employment or appraisal) for a loan application.
 *
 * <p>Workflow id contract (see {@code SPEC.md}):
 * {@code verification-{loanId}-{type}}.
 *
 * <p>Steps:
 * <ol>
 *   <li>Durable {@code workflow.sleep()} for the simulated provider latency
 *       (a Postgres-backed timer — survives crashes; never
 *       {@code Thread.sleep()} in workflow code).</li>
 *   <li>{@code callProvider} — deterministic simulated outcome; transient
 *       provider outages (amounts ending in 7 fail twice) are absorbed by the
 *       activity retry policy below and never surface to the workflow.</li>
 *   <li>{@code publishResult} — emits the result to
 *       {@code loans.verification.results} for the loan-application service.</li>
 * </ol>
 *
 * <p><b>Determinism:</b> all inputs the workflow branches on (type, amount,
 * latency) come from the persisted {@link VerificationTask} input; the code
 * between activity calls performs no I/O, no clock reads and no randomness.
 */
@DurableWorkflow(name = "verification", taskQueue = "verification-gateway")
public class VerificationWorkflow {

    // Flaky-provider simulation fails the first 2 attempts for amounts ending
    // in 7 — 4 attempts with short backoff recovers deterministically.
    @ActivityStub(
            startToCloseTimeout = "PT30S",
            retryPolicy = @RetryPolicy(
                    maxAttempts = 4,
                    initialInterval = "PT0.2S",
                    maxInterval = "PT2S",
                    backoffMultiplier = 2.0
            ))
    private VerificationProviderActivities provider;

    // Publishing the result is critical — the loan workflow is parked waiting
    // for it. Retry aggressively so transient Kafka failures don't strand it.
    @ActivityStub(
            startToCloseTimeout = "PT10S",
            retryPolicy = @RetryPolicy(
                    maxAttempts = 30,
                    initialInterval = "PT1S",
                    maxInterval = "PT1M",
                    backoffMultiplier = 2.0
            ))
    private VerificationMessagingActivities messaging;

    @WorkflowMethod
    public VerificationResult verify(VerificationTask task) {
        var workflow = WorkflowContext.current();

        // Simulated provider latency — durable timer, resolved from config
        // before start and persisted in the input (replay-stable).
        workflow.sleep(Duration.ofMillis(task.simulatedLatencyMillis()));

        var outcome = provider.callProvider(task.loanId(), task.type(), task.amount());

        var result = new VerificationResult(
                task.loanId(), task.type(), outcome.approved(), outcome.details());

        messaging.publishResult(result);

        return result;
    }
}
