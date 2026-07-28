package io.b2mash.maestro.samples.loan.verification.domain;

/**
 * Inbound verification request event consumed from the
 * {@code loans.verification.requests} Kafka topic (published by the
 * loan-application service, keyed by {@code loanId}).
 *
 * @param loanId the loan application id the verification belongs to
 * @param type   the verification type: {@code credit}, {@code employment}
 *               or {@code appraisal}
 * @param amount the requested loan amount (whole currency units; drives the
 *               deterministic simulated outcomes — see
 *               {@code SimulatedVerificationProviderActivities})
 */
public record VerificationRequest(String loanId, String type, long amount) {}
