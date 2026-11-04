package io.b2mash.maestro.store.postgres.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties the wake channel exists for: the channel is always
 * a valid, untruncated Postgres identifier, and the payload carries any
 * workflow ID verbatim.
 */
@DisplayName("WakeChannel")
class WakeChannelTest {

    @Test
    @DisplayName("a 200-char unicode service name still yields a ≤63-byte, plain identifier")
    void longUnicodeServiceNameYieldsValidIdentifier() {
        var serviceName = "Zahlungs-Dienst/ÄÖÜ€ ".repeat(10);
        assertTrue(serviceName.length() >= 200);

        var channel = WakeChannel.channelFor(serviceName);

        assertTrue(channel.getBytes(StandardCharsets.UTF_8).length <= 63,
                "Postgres truncates identifiers at 63 bytes; got " + channel.length() + ": " + channel);
        assertTrue(channel.matches("[a-z_][a-z0-9_]*"),
                "must need no quoting and survive case-folding: " + channel);
        assertTrue(channel.startsWith(WakeChannel.PREFIX));
        assertEquals(channel, WakeChannel.channelFor(serviceName), "deterministic");
    }

    @Test
    @DisplayName("service names that sanitise alike still get distinct channels")
    void namesThatSanitiseAlikeAreDistinct() {
        assertNotEquals(WakeChannel.channelFor("order-service"), WakeChannel.channelFor("order_service"));
        assertNotEquals(WakeChannel.channelFor("Order-Service"), WakeChannel.channelFor("order-service"));
        assertNotEquals(WakeChannel.channelFor("a".repeat(40)), WakeChannel.channelFor("a".repeat(41)),
                "names that differ only past the 32-char fragment still differ by hash");
    }

    @Test
    @DisplayName("two different service names → different channels")
    void differentServiceNamesDifferentChannels() {
        assertNotEquals(WakeChannel.channelFor("order-service"), WakeChannel.channelFor("payment-gateway"));
    }

    @ParameterizedTest(name = "id=''{0}''")
    @ValueSource(strings = {
            "simple",
            "with:colons:in:it",
            "line\nbreak",
            "unicode-ÄÖÜ-€-🙂",
            "12:ends-in-digits-and-colon:",
            "",
    })
    @DisplayName("encode/decode round-trips ids containing ':' '\\n' and unicode")
    void payloadRoundTrips(String workflowId) {
        for (var signalName : new String[] {"approval", "it's:odd\n", "", "ÄÖÜ"}) {
            var decoded = WakeChannel.decode(WakeChannel.encode(workflowId, signalName));
            assertEquals(workflowId, decoded.workflowId());
            assertEquals(signalName, decoded.signalName());
        }
    }

    @Test
    @DisplayName("a 300-char id round-trips")
    void longIdRoundTrips() {
        var id = "x".repeat(300);
        assertEquals(id, WakeChannel.decode(WakeChannel.encode(id, "s")).workflowId());
    }

    @ParameterizedTest(name = "payload=''{0}''")
    @ValueSource(strings = {"", "nocolon", ":abc", "abc:def", "-1:x", "10:short", "3x:abc", "2147483647:x"})
    @DisplayName("malformed payloads throw IllegalArgumentException")
    void malformedPayloadThrows(String payload) {
        assertThrows(IllegalArgumentException.class, () -> WakeChannel.decode(payload));
    }
}
