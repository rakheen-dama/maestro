package io.b2mash.maestro.samples.loan.application.domain;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * REST response body for {@code GET /applications/{id}} — the workflow
 * instance's status and output read straight from the store.
 *
 * @param applicationId the application id
 * @param workflowId    the backing workflow id ({@code loan-{applicationId}})
 * @param status        engine status (RUNNING, WAITING_SIGNAL, COMPLETED, FAILED, ...)
 * @param output        workflow output — a {@code LoanResult} when COMPLETED,
 *                      error detail when FAILED, {@code null} while running
 */
public record ApplicationStatusResponse(
        String applicationId,
        String workflowId,
        String status,
        @Nullable JsonNode output
) {}
