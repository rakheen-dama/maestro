package io.b2mash.maestro.samples.loan.application.exception;

/**
 * Thrown when the borrower withdraws the application at a withdrawal gate.
 *
 * <p>Failing the workflow with this exception triggers saga compensation for
 * any completed compensatable activities (e.g. releasing the rate lock at
 * gate #2). At gate #1 nothing has been reserved yet, so no compensation runs.
 *
 * <p><b>Note:</b> extends {@link RuntimeException} because {@code MaestroException}
 * is sealed to the engine package — application-level domain exceptions use
 * standard Java exception hierarchies.
 */
public class LoanWithdrawnException extends RuntimeException {

    public LoanWithdrawnException(String message) {
        super(message);
    }
}
