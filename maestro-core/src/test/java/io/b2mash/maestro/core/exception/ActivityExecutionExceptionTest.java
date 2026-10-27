package io.b2mash.maestro.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityExecutionExceptionTest {

    static class DomainException extends RuntimeException {
        DomainException(String m) { super(m); }
    }
    static class SpecificDomainException extends DomainException {
        SpecificDomainException(String m) { super(m); }
    }

    @Test
    @DisplayName("causedBy matches the live cause's exact type")
    void causedBy_matchesExactCauseType() {
        var e = new ActivityExecutionException("wf", "pay.charge", new DomainException("declined"));
        assertTrue(e.causedBy(DomainException.class));
    }

    @Test
    @DisplayName("causedBy matches a supertype of the cause")
    void causedBy_matchesSupertypeOfCause() {
        var e = new ActivityExecutionException("wf", "pay.charge", new SpecificDomainException("declined"));
        assertTrue(e.causedBy(DomainException.class));
        assertTrue(e.causedBy(RuntimeException.class));
    }

    @Test
    @DisplayName("causedBy is false for an unrelated type")
    void causedBy_falseForUnrelatedType() {
        var e = new ActivityExecutionException("wf", "pay.charge", new IllegalStateException("x"));
        assertFalse(e.causedBy(DomainException.class));
    }

    @Test
    @DisplayName("causedBy matches a replay-reconstructed failure by its original class name")
    void causedBy_matchesReconstructedFailureByOriginalClassName() {
        var reconstructed = new ReconstructedActivityFailure(
                SpecificDomainException.class.getName(), "declined");
        var e = new ActivityExecutionException("wf", "pay.charge", reconstructed);
        assertTrue(e.causedBy(SpecificDomainException.class));
        assertTrue(e.causedBy(DomainException.class), "subtype relation is resolved when the class loads");
        assertFalse(e.causedBy(IllegalStateException.class));
    }
}
