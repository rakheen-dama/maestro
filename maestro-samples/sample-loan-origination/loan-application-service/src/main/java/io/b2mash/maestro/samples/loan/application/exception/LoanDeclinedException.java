package io.b2mash.maestro.samples.loan.application.exception;

/**
 * Thrown when a third-party verification (credit, employment or appraisal)
 * comes back declined. No saga compensation is needed at this stage — nothing
 * has been reserved yet.
 */
public class LoanDeclinedException extends RuntimeException {

    public LoanDeclinedException(String message) {
        super(message);
    }
}
