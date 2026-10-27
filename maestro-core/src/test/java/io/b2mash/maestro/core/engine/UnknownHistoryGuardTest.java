package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C9: an unreadable-payload stand-down must name the deserializer's root cause,
 * so an operator can tell "a newer node wrote this" from "my own refactor broke
 * this" without a debugger — but (R13/R17) only by class name and field path:
 * Jackson's messages quote stored values and source text, which must never
 * reach the WARN or the observer detail.
 */
@DisplayName("UnknownHistoryGuard — payload stand-downs carry the deserialization cause")
class UnknownHistoryGuardTest {

    /** A reshaped type whose stored value Jackson would quote in its message. */
    record Shaped(int amount) {}

    private static final String MARKER = "LEAK-MARKER-7f3a";

    @Test
    void requireReadablePayload_detailNamesTheRootCause() {
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        var payload = JsonMapper.builder().build().readTree("{\"amount\":\"" + MARKER + "\"}");
        SerializationException expected = assertThrows(SerializationException.class,
                () -> serializer.deserialize(payload, Shaped.class));
        var root = expected.getCause() != null ? expected.getCause() : expected;
        assertInstanceOf(DatabindException.class, root);
        assertTrue(root.getMessage().contains(MARKER),
                "precondition: Jackson's own message quotes the stored value: " + root.getMessage());

        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                UnknownHistoryGuard.requireReadablePayload("wf", 2, "side effect",
                        () -> serializer.deserialize(payload, Shaped.class)));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, ex.kind()),
                () -> assertEquals("seq=2 cause=" + root.getClass().getName() + " at "
                                + ((DatabindException) root).getPathReference(),
                        ex.detail()),
                () -> assertTrue(ex.detail().endsWith("[\"amount\"]"),
                        "the field path, never the value: " + ex.detail()),
                () -> assertFalse(ex.detail().contains(MARKER), ex.detail()),
                () -> assertFalse(ex.getMessage().contains(MARKER), ex.getMessage()),
                () -> assertInstanceOf(SerializationException.class, ex.getCause()));
    }

    @Test
    void requireReadablePayload_rootLevelMismatchNamesTheClassOnly() {
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        var payload = JsonMapper.builder().build().readTree("{\"reshaped\":true}");
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                UnknownHistoryGuard.requireReadablePayload("wf", 2, "side effect",
                        () -> serializer.deserialize(payload, Instant.class)));
        assertEquals("seq=2 cause=tools.jackson.databind.exc.MismatchedInputException", ex.detail(),
                "an empty path adds no ' at ' suffix");
    }

    @Test
    void requireReadablePayload_streamErrorNamesTheClassOnly() {
        // A stream-level JacksonException (not a DatabindException) quotes the
        // source text it choked on — so only its class name may appear.
        var mapper = JsonMapper.builder().build();
        JacksonException streamError = assertThrows(JacksonException.class,
                () -> mapper.readTree("{\"amount\": " + MARKER + "}"));
        assertFalse(streamError instanceof DatabindException, "precondition: a stream error");
        assertTrue(streamError.getMessage().contains("LEAK"),
                "precondition: the stream error quotes the source: " + streamError.getMessage());

        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                UnknownHistoryGuard.requireReadablePayload("wf", 3, "activity result", () -> {
                    throw new SerializationException("could not read", streamError);
                }));
        assertAll(
                () -> assertEquals("seq=3 cause=" + streamError.getClass().getName(), ex.detail()),
                () -> assertFalse(ex.getMessage().contains("LEAK"), ex.getMessage()),
                () -> assertInstanceOf(SerializationException.class, ex.getCause()));
    }

    @Test
    void payloadStandDown_messageDoesNotAssumeANewerNode() {
        var ex = UnknownHistoryGuard.payloadStandDown("wf", 5, "something");
        assertAll(
                () -> assertTrue(ex.getMessage().contains("a newer node or an incompatible payload change"),
                        ex.getMessage()),
                () -> assertFalse(ex.getMessage().contains("likely written by a newer node"), ex.getMessage()));
    }

    @Test
    void payloadStandDown_detailCarriesThePhrase() {
        var ex = UnknownHistoryGuard.payloadStandDown("wf", 5,
                "a VERSION_MARKER with no payload, so it can be attributed to no change");
        assertEquals("seq=5 cause=a VERSION_MARKER with no payload, so it can be attributed to no change",
                ex.detail());
    }
}
