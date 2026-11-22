package io.b2mash.maestro.core.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.b2mash.maestro.core.annotation.FailureDetails;
import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 42: what a failed activity records ({@link FailureReconstruction#record})
 * and what both paths rebuild from that record ({@link FailureReconstruction#rebuild}).
 */
class FailureReconstructionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final PayloadSerializer SERIALIZER = new PayloadSerializer(MAPPER);

    private static ObjectNode legacy(String type, String message) {
        var node = JsonNodeFactory.instance.objectNode();
        node.put("exceptionType", type);
        node.put("message", message);
        return node;
    }

    // ── A record without details: the pre-Issue-42 rule, message kept as recorded ──

    @Test
    void aLegacyRecordRebuildsExactlyAsBefore() {
        var withCtor = FailureReconstruction.rebuild(legacy(StringCtor.class.getName(), "boom"), SERIALIZER);
        var withoutCtor = FailureReconstruction.rebuild(legacy(TwoArg.class.getName(), "boom"), SERIALIZER);
        assertAll(
                () -> assertInstanceOf(StringCtor.class, withCtor.cause()),
                () -> assertEquals("boom", withCtor.cause().getMessage()),
                () -> assertNull(withCtor.notRebuilt()),
                () -> assertEquals(StringCtor.class.getName(), withCtor.failure().type()),
                () -> assertTrue(withCtor.failure().details().isEmpty()),
                () -> assertEquals(TwoArg.class.getName(),
                        assertInstanceOf(ReconstructedActivityFailure.class, withoutCtor.cause()).originalClassName()),
                () -> assertNull(withoutCtor.notRebuilt(), "a class that never opted in is not reported"));
    }

    @Test
    void aNullMessageRebuildsAsNull() {
        var payload = legacy(StringCtor.class.getName(), null);
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertInstanceOf(StringCtor.class, rebuilt.cause()),
                () -> assertNull(rebuilt.cause().getMessage(), "not \"Replayed failure\""),
                () -> assertNull(rebuilt.failure().message()));
    }

    @Test
    void anEmptyMessageRebuildsAsEmpty() {
        var rebuilt = FailureReconstruction.rebuild(legacy(TwoArg.class.getName(), ""), SERIALIZER);
        assertEquals("", rebuilt.cause().getMessage());
    }

    @Test
    void aNullPayloadRebuildsAsAnUnknownReconstruction() {
        var rebuilt = FailureReconstruction.rebuild(null, SERIALIZER);
        assertAll(
                () -> assertEquals("unknown",
                        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause()).originalClassName()),
                () -> assertNull(rebuilt.cause().getMessage()),
                () -> assertEquals("unknown", rebuilt.failure().type()));
    }

    // ── record() ────────────────────────────────────────────────────────

    @Test
    void aClassThatHasNotOptedInRecordsTypeAndMessageOnly() {
        var payload = FailureReconstruction.record(new StringCtor("boom"), SERIALIZER);
        assertEquals(legacy(StringCtor.class.getName(), "boom"), payload);
    }

    @Test
    void anOptedInClassRecordsItsOwnPropertiesAndNoneOfThrowables() {
        var thrown = new Declined("declined", "51", 1.0E7);
        thrown.initCause(new java.io.IOException("socket"));
        thrown.addSuppressed(new IllegalStateException("also"));

        var payload = FailureReconstruction.record(thrown, SERIALIZER);

        assertAll(
                () -> assertEquals(Declined.class.getName(), payload.get("exceptionType").stringValue()),
                () -> assertEquals("declined", payload.get("message").stringValue()),
                () -> assertEquals(MAPPER.readTree("{\"reasonCode\":\"51\",\"amount\":1.0E7}"),
                        payload.get("details")));
    }

    @Test
    void anOptedInClassWhosePropertiesCannotBeWrittenRecordsNullDetails() {
        var payload = FailureReconstruction.record(new HostileGetter("boom"), SERIALIZER);
        assertTrue(payload.get("details").isNull(), payload.toString());

        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertEquals(HostileGetter.class.getName(),
                        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause()).originalClassName(),
                        "never the (String) constructor, which would drop the properties silently"),
                () -> assertEquals("boom", rebuilt.cause().getMessage()),
                () -> assertNotNull(rebuilt.notRebuilt()));
    }

    // ── rebuild() of a record with details ─────────────────────────────

    @Test
    void anOptedInClassWithACreatorRebuildsAsItselfWithItsProperties() {
        var payload = FailureReconstruction.record(new Declined("declined", "51", 2.5), SERIALIZER);
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        var declined = assertInstanceOf(Declined.class, rebuilt.cause());
        assertAll(
                () -> assertEquals("declined", declined.getMessage()),
                () -> assertEquals("51", declined.getReasonCode()),
                () -> assertEquals(2.5, declined.getAmount()),
                () -> assertNull(declined.getCause(), "the cause chain is not recorded"),
                () -> assertNull(rebuilt.notRebuilt()),
                () -> assertEquals("51", rebuilt.failure().details().get("reasonCode").stringValue()));
    }

    @Test
    void numbersTheStoreNormalisedStillRebuildFaithfully() {
        // Postgres jsonb stores 1.0E7 as 10000000, which reads back as an int node.
        var payload = (ObjectNode) MAPPER.readTree("{\"exceptionType\":\"" + Declined.class.getName()
                + "\",\"message\":\"m\",\"details\":{\"amount\":10000000,\"reasonCode\":\"05\"}}");
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertEquals(1.0E7, assertInstanceOf(Declined.class, rebuilt.cause()).getAmount()),
                () -> assertNull(rebuilt.notRebuilt()));
    }

    @Test
    void aFinalFieldWithoutACreatorFallsBackAndSaysWhy() {
        var payload = FailureReconstruction.record(new FinalNoCreator("m", "c-1"), SERIALIZER);
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertEquals(FinalNoCreator.class.getName(),
                        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause()).originalClassName()),
                () -> assertEquals("m", rebuilt.cause().getMessage()),
                () -> assertEquals("the rebuilt exception's properties differ from the recorded ones",
                        rebuilt.notRebuilt()),
                () -> assertEquals("c-1", rebuilt.failure().details().get("code").stringValue(),
                        "the recorded property stays readable"));
    }

    @Test
    void aCreatorThatRewritesTheMessageFallsBack() {
        var payload = FailureReconstruction.record(new PrefixingCreator("card expired", "51"), SERIALIZER);
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause()),
                () -> assertEquals("the rebuilt exception's message differs from the recorded one",
                        rebuilt.notRebuilt()));
    }

    @Test
    void aClassMissingOnThisNodeKeepsItsRecordedProperties() {
        var payload = (ObjectNode) MAPPER.readTree("{\"exceptionType\":\"com.example.Gone\",\"message\":\"m\","
                + "\"details\":{\"code\":\"x\"}}");
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertEquals("com.example.Gone",
                        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause()).originalClassName()),
                () -> assertEquals("x", rebuilt.failure().details().get("code").stringValue()),
                () -> assertEquals("the class is not on this node's classpath", rebuilt.notRebuilt()));
    }

    @Test
    void aControlFlowNameWithDetailsIsNeverInstantiated() {
        var payload = (ObjectNode) MAPPER.readTree("{\"exceptionType\":\"" + ExecutorShutdownException.class.getName()
                + "\",\"message\":\"m\",\"details\":{}}");
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause());
        assertNotNull(rebuilt.notRebuilt());
    }

    @Test
    void anActivityTimeoutRoundTripsAsItself() {
        var thrown = new ActivityTimeoutException("wf-1", "Pay.charge", Duration.ofMillis(300));
        var payload = FailureReconstruction.record(thrown, SERIALIZER);
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        var timeout = assertInstanceOf(ActivityTimeoutException.class, rebuilt.cause());
        assertAll(
                () -> assertEquals(thrown.getMessage(), timeout.getMessage()),
                () -> assertEquals("wf-1", timeout.workflowId()),
                () -> assertEquals("Pay.charge", timeout.activityName()),
                () -> assertEquals(Duration.ofMillis(300), timeout.timeout()),
                () -> assertNull(rebuilt.notRebuilt()));
    }

    @Test
    void anActivityTimeoutRecordedBeforeIssue42RebuildsAsAReconstruction() {
        var rebuilt = FailureReconstruction.rebuild(legacy(ActivityTimeoutException.class.getName(), "t"), SERIALIZER);
        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause());
        assertNull(rebuilt.notRebuilt());
    }

    @Test
    void sameJsonComparesNumbersByValueAndObjectsInAnyOrder() {
        JsonNode a = MAPPER.readTree("{\"x\":1.0E7,\"y\":[1,{\"z\":2.50}]}");
        JsonNode b = MAPPER.readTree("{\"y\":[1.0,{\"z\":2.5}],\"x\":10000000}");
        assertTrue(FailureReconstruction.sameJson(a, b));
        assertFalse(FailureReconstruction.sameJson(a, MAPPER.readTree("{\"x\":1.0E7,\"y\":[1,{\"z\":2.6}]}")));
        assertFalse(FailureReconstruction.sameJson(a, MAPPER.valueToTree(Map.of("x", 1))));
    }

    // ── PR #88 review ───────────────────────────────────────────────────

    @Test
    void aStringConstructorThatRewritesTheMessageIsAReconstructionNotADoubledMessage() {
        // The record holds the message the constructor already prefixed; calling
        // the constructor with it again would read "declined: declined: 51".
        var payload = FailureReconstruction.record(new Prefixing("51"), SERIALIZER);
        var rebuilt = FailureReconstruction.rebuild(payload, SERIALIZER);
        assertAll(
                () -> assertEquals(Prefixing.class.getName(),
                        assertInstanceOf(ReconstructedActivityFailure.class, rebuilt.cause()).originalClassName()),
                () -> assertEquals("declined: 51", rebuilt.cause().getMessage()),
                () -> assertEquals(rebuilt.failure().message(), rebuilt.cause().getMessage()),
                () -> assertNull(rebuilt.notRebuilt(), "the class never opted in: not reported"));
    }

    /** Each payload as the store sent it back as-is, and as a text store returns it. */
    private static java.util.List<JsonNode> storedForms(ObjectNode payload) {
        return java.util.List.of(payload, MAPPER.readTree(MAPPER.writeValueAsString(payload)));
    }

    @Test
    void numericPropertiesOfEveryKindRebuildFaithfully() {
        var thrown = new Numbers("m", 0.1f, 0.1f, 0.1, new java.math.BigDecimal("0.10"), Long.MAX_VALUE, 3);
        for (var stored : storedForms(FailureReconstruction.record(thrown, SERIALIZER))) {
            var rebuilt = FailureReconstruction.rebuild(stored, SERIALIZER);
            var numbers = assertInstanceOf(Numbers.class, rebuilt.cause(), "notRebuilt=" + rebuilt.notRebuilt());
            assertAll(
                    () -> assertEquals(0.1f, numbers.getF()),
                    () -> assertEquals(0.1f, numbers.getBoxedF()),
                    () -> assertEquals(0.1, numbers.getD()),
                    () -> assertEquals(0, new java.math.BigDecimal("0.1").compareTo(numbers.getBig())),
                    () -> assertEquals(Long.MAX_VALUE, numbers.getL()),
                    () -> assertEquals(3, numbers.getI()));
        }
    }

    @Test
    void aByteArrayPropertyRebuildsFaithfully() {
        var thrown = new Bytes("m", new byte[]{1, 2, (byte) 0xff});
        for (var stored : storedForms(FailureReconstruction.record(thrown, SERIALIZER))) {
            var rebuilt = FailureReconstruction.rebuild(stored, SERIALIZER);
            var bytes = assertInstanceOf(Bytes.class, rebuilt.cause(), "notRebuilt=" + rebuilt.notRebuilt());
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2, (byte) 0xff}, bytes.getData());
        }
    }

    @Test
    void recordedDetailsHoldOnlyJsonTextNodeKinds() {
        var payload = FailureReconstruction.record(new Bytes("m", new byte[]{9}), SERIALIZER);
        assertTrue(payload.get("details").get("data").isString(), payload.toString());
        assertTrue(FailureReconstruction.record(
                new Numbers("m", 0.1f, 0.1f, 0.1, java.math.BigDecimal.ONE, 1L, 1), SERIALIZER)
                .get("details").get("f").isDouble(), "a float is recorded as JSON text reads it");
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    public static class Prefixing extends RuntimeException {
        public Prefixing(String m) {
            super("declined: " + m);
        }
    }

    @FailureDetails
    public static class Numbers extends RuntimeException {
        private final float f;
        private final Float boxedF;
        private final double d;
        private final java.math.BigDecimal big;
        private final long l;
        private final int i;

        @JsonCreator
        public Numbers(@JsonProperty("message") String message, @JsonProperty("f") float f,
                       @JsonProperty("boxedF") Float boxedF, @JsonProperty("d") double d,
                       @JsonProperty("big") java.math.BigDecimal big, @JsonProperty("l") long l,
                       @JsonProperty("i") int i) {
            super(message);
            this.f = f;
            this.boxedF = boxedF;
            this.d = d;
            this.big = big;
            this.l = l;
            this.i = i;
        }

        public float getF() { return f; }
        public Float getBoxedF() { return boxedF; }
        public double getD() { return d; }
        public java.math.BigDecimal getBig() { return big; }
        public long getL() { return l; }
        public int getI() { return i; }
    }

    @FailureDetails
    public static class Bytes extends RuntimeException {
        private final byte[] data;

        @JsonCreator
        public Bytes(@JsonProperty("message") String message, @JsonProperty("data") byte[] data) {
            super(message);
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }
    }

    public static class StringCtor extends RuntimeException {
        public StringCtor(String m) {
            super(m);
        }
    }

    public static class TwoArg extends RuntimeException {
        public TwoArg(String m, int code) {
            super(m);
        }
    }

    @FailureDetails
    public static class Declined extends RuntimeException {
        private final String reasonCode;
        private final double amount;

        @JsonCreator
        public Declined(@JsonProperty("message") String message, @JsonProperty("reasonCode") String reasonCode,
                        @JsonProperty("amount") double amount) {
            super(message);
            this.reasonCode = reasonCode;
            this.amount = amount;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public double getAmount() {
            return amount;
        }
    }

    /** The common immutable shape the mapper rebuilds with its field silently null. */
    @FailureDetails
    public static class FinalNoCreator extends RuntimeException {
        private final String code;

        public FinalNoCreator(String m) {
            this(m, null);
        }

        public FinalNoCreator(String m, String code) {
            super(m);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    @FailureDetails
    public static class PrefixingCreator extends RuntimeException {
        private final String code;

        @JsonCreator
        public PrefixingCreator(@JsonProperty("code") String code) {
            this("declined: " + code, code);
        }

        public PrefixingCreator(String message, String code) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    @FailureDetails
    public static class HostileGetter extends RuntimeException {
        public HostileGetter(String m) {
            super(m);
        }

        public String getLazy() {
            throw new IllegalStateException("session closed");
        }
    }
}
