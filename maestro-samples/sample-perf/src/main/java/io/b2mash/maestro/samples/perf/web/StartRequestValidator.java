package io.b2mash.maestro.samples.perf.web;

import io.b2mash.maestro.samples.perf.workflow.ParkInput;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates a {@link StartRequest} against the harness's wire contract. The
 * error texts are part of that contract: the harness sees them in the
 * {@code 400} body.
 *
 * <p>Thread-safe: stateless.
 */
public final class StartRequestValidator {

    /** The workflow ID rule, shared with {@link SignalRequestValidator}. */
    static final Pattern WORKFLOW_ID = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    /** The error text for a workflow ID that breaks {@link #WORKFLOW_ID}. */
    static final String WORKFLOW_ID_ERROR = "workflowId must match [A-Za-z0-9._-]{1,200}";

    private StartRequestValidator() {}

    /**
     * Checks the first rule the request breaks, in the order workflow ID, type,
     * then the knobs of that type (knobs of the other type are ignored).
     * Omitted knobs are valid: they take {@link StartRequest}'s defaults.
     *
     * @param request the request to check
     * @return the error text of the first broken rule, or empty if the request is valid
     */
    public static Optional<String> validate(StartRequest request) {
        if (request.workflowId() == null || !WORKFLOW_ID.matcher(request.workflowId()).matches()) {
            return Optional.of(WORKFLOW_ID_ERROR);
        }
        if (StartRequest.CHAIN.equals(request.type())) {
            var input = request.chainInput();
            if (input.activities() < 1 || input.activities() > 50) {
                return Optional.of("activities must be 1..50");
            }
            if (input.activityMillis() < 0 || input.activityMillis() > 10_000) {
                return Optional.of("activityMillis must be 0..10000");
            }
            return Optional.empty();
        }
        if (StartRequest.PARK.equals(request.type())) {
            var input = request.parkInput();
            if (!ParkInput.SIGNAL.equals(input.parkMode()) && !ParkInput.SLEEP.equals(input.parkMode())) {
                return Optional.of("parkMode must be signal or sleep");
            }
            if (input.parkSeconds() < 1 || input.parkSeconds() > 86_400) {
                return Optional.of("parkSeconds must be 1..86400");
            }
            return Optional.empty();
        }
        if (StartRequest.HOLD.equals(request.type())) {
            var input = request.holdInput();
            if (input.activities() < 1 || input.activities() > 50) {
                return Optional.of("activities must be 1..50");
            }
            if (input.holdMillis() < 0 || input.holdMillis() > 20_000) {
                return Optional.of("holdMillis must be 0..20000");
            }
            return Optional.empty();
        }
        if (StartRequest.SIGNALS.equals(request.type())) {
            var input = request.signalsInput();
            if (input.signals() < 1 || input.signals() > 50) {
                return Optional.of("signals must be 1..50");
            }
            if (input.parkSeconds() < 1 || input.parkSeconds() > 86_400) {
                return Optional.of("parkSeconds must be 1..86400");
            }
            return Optional.empty();
        }
        return Optional.of("type must be chain, park, hold or signals");
    }
}
