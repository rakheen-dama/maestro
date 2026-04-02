package io.b2mash.maestro.samples.document.domain;

/**
 * HTTP request body to submit a new document for approval.
 *
 * @param title    document title
 * @param content  document body content
 * @param authorId identifier of the author
 */
public record SubmitDocumentRequest(
    String title,
    String content,
    String authorId
) {}
