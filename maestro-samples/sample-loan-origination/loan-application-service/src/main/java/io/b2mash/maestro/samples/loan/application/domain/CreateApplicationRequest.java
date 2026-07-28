package io.b2mash.maestro.samples.loan.application.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * REST request body for {@code POST /applications}.
 *
 * @param applicationId optional client-supplied id (generated when absent) —
 *                      supplying one enables the pre-delivery demo (upload
 *                      documents to a predetermined id before creating the app)
 */
public record CreateApplicationRequest(
        @Nullable String applicationId,
        List<String> borrowerIds,
        long amount,
        long income,
        long propertyValue,
        List<String> requiredDocs
) {}
