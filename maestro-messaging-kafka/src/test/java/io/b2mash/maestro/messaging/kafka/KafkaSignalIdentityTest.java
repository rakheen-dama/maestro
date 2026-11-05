package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.context.WorkflowContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Broker-free tests for the message-id resolution order (envelope → header →
 * topic-id coordinate), the topic-id cache and the producer helpers.
 */
@DisplayName("KafkaSignalIdentity, MaestroSignalHeaders, SignalMessageIds")
class KafkaSignalIdentityTest {

    private static ConsumerRecord<String, byte[]> record(String topic, int partition, long offset,
                                                         String headerId) {
        var record = new ConsumerRecord<String, byte[]>(topic, partition, offset, "key", new byte[0]);
        if (headerId != null) {
            MaestroSignalHeaders.setMessageId(record.headers(), headerId);
        }
        return record;
    }

    @Test
    @DisplayName("envelope beats header beats coordinate; the coordinate uses the topic id, never the name")
    void resolutionOrder() {
        var lookups = new AtomicInteger();
        var identity = new KafkaSignalIdentity(topic -> {
            lookups.incrementAndGet();
            return "TOPICID";
        }, System::nanoTime);

        assertEquals("env:e", identity.resolve("e", record("t", 0, 5, "h")),
                "a mismatching header loses to the envelope");
        assertEquals("env:e", identity.resolve("e", record("t", 0, 5, null)));
        assertEquals("hdr:t:h", identity.resolve(null, record("t", 0, 5, "h")));
        assertEquals("hdr:t:h", identity.resolve(" ", record("t", 0, 5, "h")), "a blank envelope id is absent");
        assertEquals("hdr:t:h", identity.resolve("bad\u0000id", record("t", 0, 5, "h")),
                "an envelope id with a control character is ignored");
        assertEquals(0, lookups.get(), "no topic lookup while a producer id exists");

        assertEquals("kafka:TOPICID:2:41", identity.resolve(null, record("t", 2, 41, null)));
        assertEquals("kafka:TOPICID:2:41", identity.resolve(null, record("t", 2, 41, null)),
                "a redelivery of the same record resolves to the same id");
        assertEquals(1, lookups.get(), "the topic id is cached");
    }

    @Test
    @DisplayName("sources cannot forge each other's keys: a header spelling a coordinate, or another topic's id, "
            + "never collides")
    void namespacesPreventSpoofing() {
        var identity = new KafkaSignalIdentity(topic -> "TOPICID", System::nanoTime);

        // A producer sets the header to the literal coordinate of a record that
        // has not arrived yet...
        var forged = identity.resolve(null, record("t", 0, 5, "kafka:TOPICID:0:7"));
        // ...and the genuine, header-less record later lands at that offset.
        var genuine = identity.resolve(null, record("t", 0, 7, null));
        assertEquals("kafka:TOPICID:0:7", genuine);
        org.junit.jupiter.api.Assertions.assertNotEquals(forged, genuine,
                "the forged header must not suppress the genuine signal");

        assertEquals("hdr:a:same", identity.resolve(null, record("a", 0, 1, "same")));
        assertEquals("hdr:b:same", identity.resolve(null, record("b", 0, 1, "same")),
                "one topic's header ids cannot suppress another topic's signals");
        org.junit.jupiter.api.Assertions.assertNotEquals(identity.resolve("x", record("t", 0, 1, null)),
                identity.resolve(null, record("t", 0, 1, "x")), "envelope and header ids live apart");
    }

    @Test
    @DisplayName("poison header values are no identity: NUL, other control characters, more than 512 bytes")
    void poisonHeadersAreIgnored() {
        var withNul = new RecordHeaders();
        withNul.add(MaestroSignalHeaders.MESSAGE_ID, "a\u0000b".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(MaestroSignalHeaders.messageId(withNul));

        for (var bad : List.of("tab\there", "line\nbreak", "esc\u001b", "del\u007f")) {
            var headers = new RecordHeaders();
            headers.add(MaestroSignalHeaders.MESSAGE_ID, bad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertNull(MaestroSignalHeaders.messageId(headers), bad);
        }

        var overLong = new RecordHeaders();
        overLong.add(MaestroSignalHeaders.MESSAGE_ID, "é".repeat(257).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(MaestroSignalHeaders.messageId(overLong), "514 UTF-8 bytes exceeds the 512-byte limit");
        var atLimit = new RecordHeaders();
        atLimit.add(MaestroSignalHeaders.MESSAGE_ID, "é".repeat(256).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("é".repeat(256), MaestroSignalHeaders.messageId(atLimit));

        var identity = new KafkaSignalIdentity(topic -> "TOPICID", System::nanoTime);
        var record = record("t", 3, 9, null);
        record.headers().add(MaestroSignalHeaders.MESSAGE_ID, "x\u0000".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("kafka:TOPICID:3:9", identity.resolve(null, record),
                "an invalid header falls back to the coordinate, never fails the record");
    }

    @Test
    @DisplayName("an id that is within the header limit but too long once namespaced falls through to the next source")
    void overLongNamespacedIdFallsThrough() {
        var identity = new KafkaSignalIdentity(topic -> "TOPICID", System::nanoTime);
        var longTopic = "t".repeat(100);
        var nearLimit = "h".repeat(500); // 500 bytes passes the header check; "hdr:<100>:" + 500 > 512
        assertEquals("kafka:TOPICID:0:4", identity.resolve(null, record(longTopic, 0, 4, nearLimit)),
                "an identity the engine would discard must not displace the coordinate");
        assertEquals("kafka:TOPICID:0:4", identity.resolve("e".repeat(510), record(longTopic, 0, 4, null)),
                "same for an envelope id");
        assertEquals("hdr:" + longTopic + ":ok", identity.resolve("e".repeat(510), record(longTopic, 0, 4, "ok")),
                "an over-long envelope id falls through to a valid header");
    }

    @Test
    @DisplayName("an unresolvable topic id means no identity, retried at most once per interval")
    void unresolvedTopicIdIsNoIdentityAndRetriedLater() {
        var now = new AtomicLong(0);
        var lookups = new AtomicInteger();
        var answer = new String[] {null};
        var identity = new KafkaSignalIdentity(topic -> {
            lookups.incrementAndGet();
            return answer[0];
        }, now::get);

        assertNull(identity.resolve(null, record("t", 0, 1, null)));
        assertNull(identity.resolve(null, record("t", 0, 2, null)));
        assertEquals(1, lookups.get(), "a failed lookup is not repeated per record");

        answer[0] = "LATER";
        now.addAndGet(KafkaSignalIdentity.RETRY_INTERVAL.toNanos());
        assertEquals("kafka:LATER:0:3", identity.resolve(null, record("t", 0, 3, null)));
        assertEquals(2, lookups.get());
    }

    @Test
    @DisplayName("a partition (re)assignment drops the cached id — a recreated topic is looked up afresh")
    void reassignmentInvalidatesTheCache() {
        var ids = new java.util.ArrayDeque<>(List.of("FIRST", "SECOND"));
        var identity = new KafkaSignalIdentity(topic -> ids.poll(), System::nanoTime);

        assertEquals("kafka:FIRST:0:0", identity.resolve(null, record("t", 0, 0, null)));
        identity.rebalanceListener().onPartitionsAssigned(List.of(new TopicPartition("t", 0)));
        assertEquals("kafka:SECOND:0:0", identity.resolve(null, record("t", 0, 0, null)),
                "offset 0 of the new incarnation must not collide with offset 0 of the old one");
    }

    @Test
    @DisplayName("MaestroSignalHeaders: set replaces, blank reads as absent, blank ids are rejected")
    void headers() {
        var headers = new RecordHeaders();
        assertNull(MaestroSignalHeaders.messageId(headers));
        MaestroSignalHeaders.setMessageId(headers, "a");
        MaestroSignalHeaders.setMessageId(headers, "b");
        assertEquals("b", MaestroSignalHeaders.messageId(headers));
        assertEquals(1, headers.toArray().length);
        headers.add(MaestroSignalHeaders.MESSAGE_ID, " ".getBytes());
        assertNull(MaestroSignalHeaders.messageId(headers));
        assertThrows(IllegalArgumentException.class, () -> MaestroSignalHeaders.setMessageId(headers, " "));
    }

    @Test
    @DisplayName("SignalMessageIds: <workflowId>:<activitySequence>[:<suffix>] from the bound context")
    void signalMessageIds() throws Exception {
        assertThrows(IllegalStateException.class, SignalMessageIds::forCurrentActivity,
                "outside a workflow there is no stable identity to derive");

        var ctx = new WorkflowContext(UUID.randomUUID(), "loan-7", UUID.randomUUID(), "LoanWorkflow",
                "default", "svc", 0, false);
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            io.b2mash.maestro.core.context.WorkflowContextInternals.nextSequence(ctx);
            io.b2mash.maestro.core.context.WorkflowContextInternals.nextSequence(ctx);
            assertEquals("loan-7:2", SignalMessageIds.forCurrentActivity());
            assertEquals("loan-7:2:credit", SignalMessageIds.forCurrentActivity("credit"));
            assertThrows(IllegalArgumentException.class, () -> SignalMessageIds.forCurrentActivity(""));
        });
    }
}
