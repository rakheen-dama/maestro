package io.b2mash.maestro.samples.document.domain;

/**
 * Input for the document approval workflow.
 *
 * @param title    document title
 * @param content  document body content
 * @param authorId identifier of the author who submitted the document
 */
public record DocumentInput(
    String title,
    String content,
    String authorId
) {}
