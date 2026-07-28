package io.b2mash.maestro.samples.loan.application.domain;

/** REST request body for {@code POST /applications/{id}/documents}. */
public record UploadDocumentRequest(String docType, String uploadedBy) {}
