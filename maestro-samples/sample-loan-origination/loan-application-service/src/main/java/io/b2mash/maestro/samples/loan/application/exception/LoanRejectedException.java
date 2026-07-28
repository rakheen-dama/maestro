package io.b2mash.maestro.samples.loan.application.exception;

/**
 * Thrown when underwriting rejects the application (including a CONDITIONS
 * verdict on the final round, which is treated as REJECTED) or when signature
 * collection exceeds its bound.
 */
public class LoanRejectedException extends RuntimeException {

    public LoanRejectedException(String message) {
        super(message);
    }
}
