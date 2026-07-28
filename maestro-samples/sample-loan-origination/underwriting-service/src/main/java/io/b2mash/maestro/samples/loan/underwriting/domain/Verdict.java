package io.b2mash.maestro.samples.loan.underwriting.domain;

/**
 * The outcome of an underwriting decision, whether reached automatically
 * or by a human underwriter.
 *
 * <p>Serialized as a plain string ({@code "APPROVED"}, {@code "REJECTED"},
 * {@code "CONDITIONS"}) in signal payloads and Kafka events — the exact
 * verdict vocabulary defined by the sample SPEC.
 */
public enum Verdict {

    /** The loan is approved as applied for. */
    APPROVED,

    /** The loan is rejected. */
    REJECTED,

    /** Approval is conditional — the borrower must satisfy the listed conditions. */
    CONDITIONS
}
