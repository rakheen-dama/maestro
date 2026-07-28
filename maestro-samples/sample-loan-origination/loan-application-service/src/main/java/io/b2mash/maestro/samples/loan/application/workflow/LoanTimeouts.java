package io.b2mash.maestro.samples.loan.application.workflow;

import java.time.Duration;

/**
 * Timeout configuration used by {@link LoanApplicationWorkflow}.
 *
 * <p>Workflow instances are created reflectively by the engine (no Spring
 * injection into workflow classes), so the {@code maestro.sample.*} Duration
 * properties are bound via {@code LoanSampleProperties} and copied into this
 * static holder at application startup. Tests call {@link #configure} directly
 * to shrink the timeouts (≤1s) and {@link #reset} to restore SPEC defaults.
 *
 * <p>All fields are {@code volatile}: they are written once at startup (or by
 * a test setup method) and read from workflow virtual threads.
 */
public final class LoanTimeouts {

    /** SPEC default: how long to wait for required/condition documents. */
    public static final Duration DEFAULT_DOC_TIMEOUT = Duration.ofMinutes(10);
    /** SPEC default: how long to wait for closing-package signatures. */
    public static final Duration DEFAULT_SIGN_TIMEOUT = Duration.ofMinutes(10);
    /** SPEC default: how long to wait for verification/underwriting decisions. */
    public static final Duration DEFAULT_DECISION_TIMEOUT = Duration.ofMinutes(10);
    /** SPEC default: short await at each withdrawal gate (timeout = not withdrawn). */
    public static final Duration DEFAULT_GATE_TIMEOUT = Duration.ofSeconds(1);

    private static volatile Duration docTimeout = DEFAULT_DOC_TIMEOUT;
    private static volatile Duration signTimeout = DEFAULT_SIGN_TIMEOUT;
    private static volatile Duration decisionTimeout = DEFAULT_DECISION_TIMEOUT;
    private static volatile Duration gateTimeout = DEFAULT_GATE_TIMEOUT;

    private LoanTimeouts() {
    }

    /** Applies the given timeouts (called at startup from Spring config, or from tests). */
    public static void configure(Duration doc, Duration sign, Duration decision, Duration gate) {
        docTimeout = doc;
        signTimeout = sign;
        decisionTimeout = decision;
        gateTimeout = gate;
    }

    /** Restores the SPEC defaults (call from test teardown). */
    public static void reset() {
        configure(DEFAULT_DOC_TIMEOUT, DEFAULT_SIGN_TIMEOUT,
                DEFAULT_DECISION_TIMEOUT, DEFAULT_GATE_TIMEOUT);
    }

    public static Duration docTimeout() {
        return docTimeout;
    }

    public static Duration signTimeout() {
        return signTimeout;
    }

    public static Duration decisionTimeout() {
        return decisionTimeout;
    }

    public static Duration gateTimeout() {
        return gateTimeout;
    }
}
