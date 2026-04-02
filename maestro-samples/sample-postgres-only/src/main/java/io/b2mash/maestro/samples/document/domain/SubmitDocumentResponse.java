package io.b2mash.maestro.samples.document.domain;

/**
 * HTTP response after a document is submitted for approval.
 *
 * @param documentId the generated document identifier
 */
public record SubmitDocumentResponse(String documentId) {}
