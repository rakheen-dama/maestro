package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MaestroSignalListenerBeanPostProcessor} — annotation
 * scanning and validation logic.
 *
 * <p>These tests verify the processor correctly discovers annotated methods,
 * validates method signatures, and rejects invalid ones with clear errors.
 */
class MaestroSignalListenerBeanPostProcessorTest {

    private MaestroSignalListenerBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MaestroSignalListenerBeanPostProcessor();
    }

    // ── Test beans ───────────────────────────────────────────────────────

    /** Valid: one param, returns SignalRouting, public. */
    public static class ValidListenerBean {
        @MaestroSignalListener(topic = "test.topic", signalName = "test.signal")
        public SignalRouting handleEvent(String event) {
            return SignalRouting.builder()
                    .workflowId("wf-" + event)
                    .build();
        }
    }

    /** Valid: multiple listener methods on the same bean. */
    public static class MultiListenerBean {
        @MaestroSignalListener(topic = "topic.a", signalName = "signal.a")
        public SignalRouting handleA(String event) {
            return SignalRouting.builder().workflowId("a-" + event).build();
        }

        @MaestroSignalListener(topic = "topic.b", signalName = "signal.b", groupIdSuffix = "group-b")
        public SignalRouting handleB(Integer event) {
            return SignalRouting.builder().workflowId("b-" + event).build();
        }
    }

    /** Invalid: wrong return type. */
    public static class WrongReturnTypeBean {
        @MaestroSignalListener(topic = "test.topic", signalName = "test.signal")
        public String handleEvent(String event) {
            return event;
        }
    }

    /** Invalid: no parameters. */
    public static class NoParamBean {
        @MaestroSignalListener(topic = "test.topic", signalName = "test.signal")
        public SignalRouting handleEvent() {
            return SignalRouting.builder().workflowId("none").build();
        }
    }

    /** Invalid: too many parameters. */
    public static class TooManyParamsBean {
        @MaestroSignalListener(topic = "test.topic", signalName = "test.signal")
        public SignalRouting handleEvent(String a, String b) {
            return SignalRouting.builder().workflowId(a).build();
        }
    }

    /** Bean with no annotations — should pass through untouched. */
    public static class PlainBean {
        public void doNothing() {}
    }

    // ── Scanning tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Annotation scanning")
    class ScanningTests {

        @Test
        @DisplayName("discovers valid @MaestroSignalListener method")
        void discoversValidListener() {
            var bean = new ValidListenerBean();
            var result = processor.postProcessAfterInitialization(bean, "validBean");
            assertSame(bean, result, "Bean should be returned unchanged");
        }

        @Test
        @DisplayName("discovers multiple listeners on same bean")
        void discoversMultipleListeners() {
            var bean = new MultiListenerBean();
            var result = processor.postProcessAfterInitialization(bean, "multiBean");
            assertSame(bean, result);
        }

        @Test
        @DisplayName("plain bean passes through without error")
        void plainBeanPassesThrough() {
            var bean = new PlainBean();
            var result = processor.postProcessAfterInitialization(bean, "plainBean");
            assertSame(bean, result);
        }
    }

    // ── Validation tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Method validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects method with wrong return type")
        void rejectsWrongReturnType() {
            var bean = new WrongReturnTypeBean();
            var ex = assertThrows(BeanInitializationException.class,
                    () -> processor.postProcessAfterInitialization(bean, "wrongReturn"));
            assertTrue(ex.getMessage().contains("must return SignalRouting"),
                    "Error should mention return type: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("java.lang.String"),
                    "Error should show actual return type: " + ex.getMessage());
        }

        @Test
        @DisplayName("rejects method with no parameters")
        void rejectsNoParams() {
            var bean = new NoParamBean();
            var ex = assertThrows(BeanInitializationException.class,
                    () -> processor.postProcessAfterInitialization(bean, "noParam"));
            assertTrue(ex.getMessage().contains("exactly one parameter"),
                    "Error should mention parameter count: " + ex.getMessage());
        }

        @Test
        @DisplayName("rejects method with too many parameters")
        void rejectsTooManyParams() {
            var bean = new TooManyParamsBean();
            var ex = assertThrows(BeanInitializationException.class,
                    () -> processor.postProcessAfterInitialization(bean, "tooManyParams"));
            assertTrue(ex.getMessage().contains("exactly one parameter"),
                    "Error should mention parameter count: " + ex.getMessage());
        }
    }

    // ── SignalRouting builder tests ───────────────────────────────────────

    @Nested
    @DisplayName("SignalRouting builder")
    class SignalRoutingTests {

        @Test
        @DisplayName("builds with workflowId and payload")
        void buildsWithPayload() {
            var routing = SignalRouting.builder()
                    .workflowId("order-123")
                    .payload(new Object())
                    .build();
            assertEquals("order-123", routing.workflowId());
            assertNotNull(routing.payload());
        }

        @Test
        @DisplayName("builds with null payload")
        void buildsWithNullPayload() {
            var routing = SignalRouting.builder()
                    .workflowId("order-456")
                    .build();
            assertEquals("order-456", routing.workflowId());
            assertNull(routing.payload());
        }

        @Test
        @DisplayName("throws on missing workflowId")
        void throwsOnMissingWorkflowId() {
            assertThrows(NullPointerException.class, () ->
                    SignalRouting.builder().build());
        }
    }
}
