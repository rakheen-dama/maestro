package io.b2mash.maestro.samples.document.domain;

import org.jspecify.annotations.Nullable;

/**
 * A reviewer's decision on a document, delivered as a Maestro signal.
 *
 * @param approved   whether the reviewer approved the document
 * @param reviewerId identifier of the reviewer
 * @param comments   optional reviewer comments (required on rejection)
 */
public record ReviewDecision(
    boolean approved,
    String reviewerId,
    @Nullable String comments
) {}
