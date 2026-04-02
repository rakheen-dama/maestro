package io.b2mash.maestro.samples.document.domain;

/**
 * Current status of a document approval workflow, returned by the query method.
 *
 * @param currentStep the workflow step currently being executed
 */
public record DocumentStatus(String currentStep) {}
