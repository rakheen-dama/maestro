package io.b2mash.maestro.samples.loan.application.domain;

/** REST request body for {@code POST /applications/{id}/withdraw}. */
public record WithdrawRequest(String reason) {}
