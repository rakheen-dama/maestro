package io.b2mash.maestro.samples.perf;

import io.b2mash.maestro.samples.perf.web.SignalRequestValidator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One case per {@link SignalRequestValidator} bound, each asserting the exact
 * error text the harness sees in a 400 body, plus the inclusive limits.
 */
class SignalRequestValidatorTest {

    private static final String ID_ERROR = "workflowId must match [A-Za-z0-9._-]{1,200}";
    private static final String NAME_ERROR = "signal name must be 1..255 characters";
    private static final String PAYLOAD_ERROR = "payload must be at most 1024 characters";

    @Test
    void workflowIdWithForbiddenCharacter() {
        assertEquals(Optional.of(ID_ERROR), SignalRequestValidator.validate("run 1", "perf.release", null));
    }

    @Test
    void workflowIdTooLong() {
        assertEquals(Optional.of(ID_ERROR),
                SignalRequestValidator.validate("a".repeat(201), "perf.release", null));
    }

    @Test
    void workflowIdEmpty() {
        assertEquals(Optional.of(ID_ERROR), SignalRequestValidator.validate("", "perf.release", null));
    }

    @Test
    void nameTooLong() {
        assertEquals(Optional.of(NAME_ERROR), SignalRequestValidator.validate("r1", "n".repeat(256), null));
    }

    @Test
    void nameEmpty() {
        assertEquals(Optional.of(NAME_ERROR), SignalRequestValidator.validate("r1", "", null));
    }

    @Test
    void payloadTooLong() {
        assertEquals(Optional.of(PAYLOAD_ERROR),
                SignalRequestValidator.validate("r1", "perf.release", "p".repeat(1025)));
    }

    @Test
    void boundsAreInclusive() {
        assertEquals(Optional.empty(),
                SignalRequestValidator.validate("a".repeat(200), "n".repeat(255), "p".repeat(1024)));
    }

    @Test
    void payloadIsOptional() {
        assertEquals(Optional.empty(), SignalRequestValidator.validate("r1", "perf.release", null));
    }
}
