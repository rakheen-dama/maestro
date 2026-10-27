package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C9: an unreadable-payload stand-down must name the deserializer's root cause,
 * so an operator can tell "a newer node wrote this" from "my own refactor broke
 * this" without a debugger.
 */
@DisplayName("UnknownHistoryGuard — payload stand-downs carry the deserialization cause")
class UnknownHistoryGuardTest {

    @Test
    void requireReadablePayload_detailNamesTheRootCause() {
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        var payload = JsonMapper.builder().build().readTree("{\"reshaped\":true}");
        SerializationException expected = assertThrows(SerializationException.class,
                () -> serializer.deserialize(payload, Instant.class));
        var root = expected.getCause() != null ? expected.getCause() : expected;

        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                UnknownHistoryGuard.requireReadablePayload("wf", 2, "side effect",
                        () -> serializer.deserialize(payload, Instant.class)));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, ex.kind()),
                () -> assertEquals("seq=2 cause=" + root.getClass().getName() + ": " + root.getMessage(),
                        ex.detail()),
                () -> assertInstanceOf(SerializationException.class, ex.getCause()));
    }

    @Test
    void payloadStandDown_detailCarriesThePhrase() {
        var ex = UnknownHistoryGuard.payloadStandDown("wf", 5,
                "a VERSION_MARKER with no payload, so it can be attributed to no change");
        assertEquals("seq=5 cause=a VERSION_MARKER with no payload, so it can be attributed to no change",
                ex.detail());
    }
}
