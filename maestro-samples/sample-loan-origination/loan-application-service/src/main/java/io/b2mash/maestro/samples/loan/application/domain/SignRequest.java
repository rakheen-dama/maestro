package io.b2mash.maestro.samples.loan.application.domain;

/** REST request body for {@code POST /applications/{id}/sign}. */
public record SignRequest(String signerId) {}
