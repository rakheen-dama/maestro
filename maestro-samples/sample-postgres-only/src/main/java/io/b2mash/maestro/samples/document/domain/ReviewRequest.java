package io.b2mash.maestro.samples.document.domain;

import org.jspecify.annotations.Nullable;

/**
 * HTTP request body for submitting a review decision.
 *
 * @param approved   whether the reviewer approves the document
 * @param reviewerId identifier of the reviewer
 * @param comments   optional reviewer comments
 */
public record ReviewRequest(
    boolean approved,
    String reviewerId,
    @Nullable String comments
) {}
