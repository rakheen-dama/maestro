package io.b2mash.maestro.samples.payment.workflow;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.payment.activity.PaymentMessagingActivities;
import io.b2mash.maestro.samples.payment.activity.PaymentProviderActivities;
import io.b2mash.maestro.samples.payment.domain.PaymentConfirmation;
import io.b2mash.maestro.samples.payment.domain.PaymentProcessingResult;
import io.b2mash.maestro.samples.payment.domain.PaymentRequest;
import io.b2mash.maestro.samples.payment.domain.PaymentResult;
import io.b2mash.maestro.samples.payment.exception.PaymentDeclinedException;
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-memory tests for {@link PaymentProcessingWorkflow} using
 * {@link TestWorkflowEnvironment}.
 */
class PaymentProcessingWorkflowTest {

    private TestWorkflowEnvironment env;
    private CountingProvider provider;
    private RecordingMessaging messaging;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        provider = new CountingProvider();
        messaging = new RecordingMessaging();
        env.registerActivities(PaymentProviderActivities.class, provider);
        env.registerActivities(PaymentMessagingActivities.class, messaging);
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
    }

    @Test
    void declinedCard_yieldsDeclinedResultAfterExactlyOneProviderAttempt() throws TimeoutException {
        var request = new PaymentRequest("order-1", "DECLINE_ME", new BigDecimal("19.99"));
        var handle = env.startWorkflow("payment-order-1", PaymentProcessingWorkflow.class, request);

        var result = handle.getResult(PaymentProcessingResult.class, Duration.ofSeconds(5));

        assertFalse(result.success());
        assertEquals("Card permanently declined", result.reason());
        assertNull(result.transactionId());
        assertEquals(1, provider.calls.get(), "a declined card is non-retryable: exactly one provider attempt");
        assertEquals(List.of(PaymentResult.failed("Card permanently declined")), messaging.published);
        assertEquals(WorkflowStatus.COMPLETED, handle.getStatus(),
                "a decline is a business outcome, not a workflow failure");
    }

    @Test
    void approvedCard_yieldsSuccessAndPublishesTheTransaction() throws TimeoutException {
        var request = new PaymentRequest("order-2", "VISA-OK", new BigDecimal("5.00"));
        var handle = env.startWorkflow("payment-order-2", PaymentProcessingWorkflow.class, request);

        var result = handle.getResult(PaymentProcessingResult.class, Duration.ofSeconds(5));

        assertTrue(result.success());
        assertEquals("txn-1", result.transactionId());
        assertEquals(1, provider.calls.get());
        assertEquals(List.of(PaymentResult.success("txn-1")), messaging.published);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    static class CountingProvider implements PaymentProviderActivities {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public PaymentConfirmation charge(String paymentMethod, BigDecimal amount) {
            calls.incrementAndGet();
            if ("DECLINE_ME".equals(paymentMethod)) {
                throw new PaymentDeclinedException("Card permanently declined");
            }
            return new PaymentConfirmation("txn-1");
        }
    }

    static class RecordingMessaging implements PaymentMessagingActivities {
        final List<PaymentResult> published = new CopyOnWriteArrayList<>();

        @Override
        public void publishPaymentResult(String orderId, PaymentResult result) {
            published.add(result);
        }
    }
}
