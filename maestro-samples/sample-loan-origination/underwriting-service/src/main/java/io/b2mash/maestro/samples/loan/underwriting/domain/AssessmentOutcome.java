package io.b2mash.maestro.samples.loan.underwriting.domain;

/**
 * The outcome of the automatic rule-based assessment.
 *
 * <p>Rules (DTI = amount / income):
 * <ul>
 *   <li>DTI &lt; 3 and all verifications approved → {@link #AUTO_APPROVE}</li>
 *   <li>DTI &gt; 6 → {@link #AUTO_REJECT}</li>
 *   <li>anything else → {@link #HUMAN_REVIEW}</li>
 * </ul>
 */
public enum AssessmentOutcome {

    /** Clear-cut approval; no human involvement needed. */
    AUTO_APPROVE,

    /** Clear-cut rejection; no human involvement needed. */
    AUTO_REJECT,

    /** Borderline — route to the human underwriter queue. */
    HUMAN_REVIEW
}
