package io.b2mash.maestro.samples.perf.web;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Validates {@code POST /perf/workflows/{id}/signals/{name}} against the
 * harness's wire contract, so an out-of-bounds request is a {@code 400} rather
 * than an unbounded row in the signal table. The error texts are part of that
 * contract: the harness sees them in the {@code 400} body.
 *
 * <p>Thread-safe: stateless.
 */
public final class SignalRequestValidator {

    /** Longest accepted signal name. */
    public static final int MAX_NAME_LENGTH = 255;

    /** Longest accepted payload. */
    public static final int MAX_PAYLOAD_LENGTH = 1024;

    private SignalRequestValidator() {}

    /**
     * Checks the first rule the request breaks, in the order workflow ID (the
     * same rule as a start), signal name, then payload.
     *
     * @param workflowId the path's workflow ID
     * @param name       the path's signal name
     * @param payload    the body's payload, or {@code null} (no body, or no payload)
     * @return the error text of the first broken rule, or empty if the request is valid
     */
    public static Optional<String> validate(String workflowId, String name, @Nullable String payload) {
        if (!StartRequestValidator.WORKFLOW_ID.matcher(workflowId).matches()) {
            return Optional.of(StartRequestValidator.WORKFLOW_ID_ERROR);
        }
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return Optional.of("signal name must be 1.." + MAX_NAME_LENGTH + " characters");
        }
        if (payload != null && payload.length() > MAX_PAYLOAD_LENGTH) {
            return Optional.of("payload must be at most " + MAX_PAYLOAD_LENGTH + " characters");
        }
        return Optional.empty();
    }
}
