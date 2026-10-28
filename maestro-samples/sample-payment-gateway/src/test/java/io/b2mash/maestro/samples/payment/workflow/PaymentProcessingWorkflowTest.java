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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private WedgingProvider wedging;

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
        if (wedging != null) {
            wedging.releaseAll();
        }
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

    @Test
    void approvedCard_publishesTheResultUnderItsOrderId() throws TimeoutException {
        var request = new PaymentRequest("order-3", "VISA-OK", new BigDecimal("7.50"));
        var handle = env.startWorkflow("payment-order-3", PaymentProcessingWorkflow.class, request);

        var result = handle.getResult(PaymentProcessingResult.class, Duration.ofSeconds(5));

        assertEquals(PaymentProcessingResult.success("txn-1"), result);
        assertEquals(List.of("order-3"), messaging.publishedOrderIds);
    }

    @Test
    void twoConcurrentPaymentsCompleteIndependently() throws TimeoutException {
        // The provider has no order ID, so the fake tells runs apart by payment method.
        wedging = new WedgingProvider();
        env.registerActivities(PaymentProviderActivities.class, wedging);
        wedging.block("card-B");

        var a = env.startWorkflow("payment-A", PaymentProcessingWorkflow.class,
                new PaymentRequest("A", "card-A", new BigDecimal("1.00")));
        var b = env.startWorkflow("payment-B", PaymentProcessingWorkflow.class,
                new PaymentRequest("B", "card-B", new BigDecimal("2.00")));
        wedging.awaitBlocked("card-B");

        var resultA = a.getResult(PaymentProcessingResult.class, Duration.ofSeconds(10));
        assertEquals(PaymentProcessingResult.success("txn-card-A"), resultA,
                "A completes with its own transaction while B is still charging");
        assertEquals(WorkflowStatus.RUNNING, b.getStatus());

        wedging.release("card-B");
        var resultB = b.getResult(PaymentProcessingResult.class, Duration.ofSeconds(10));
        assertEquals(PaymentProcessingResult.success("txn-card-B"), resultB);
        assertEquals(Set.of("A", "B"), Set.copyOf(messaging.publishedOrderIds));
        assertEquals(2, messaging.publishedOrderIds.size(), "one result published per order");
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
        final List<String> publishedOrderIds = new CopyOnWriteArrayList<>();

        @Override
        public void publishPaymentResult(String orderId, PaymentResult result) {
            published.add(result);
            publishedOrderIds.add(orderId);
        }
    }

    /**
     * Charges successfully with a transaction ID derived from the payment method,
     * blocking while that payment method is {@linkplain #block held}.
     */
    static class WedgingProvider implements PaymentProviderActivities {
        private final ConcurrentHashMap<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CountDownLatch> arrivals = new ConcurrentHashMap<>();

        void block(String paymentMethod) {
            gates.put(paymentMethod, new CountDownLatch(1));
            arrivals.put(paymentMethod, new CountDownLatch(1));
        }

        /** Waits until a run is blocked inside {@code charge} for {@code paymentMethod}. */
        void awaitBlocked(String paymentMethod) {
            try {
                assertTrue(arrivals.get(paymentMethod).await(10, TimeUnit.SECONDS),
                        "no run ever reached charge for '" + paymentMethod + "'");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void releaseAll() {
            gates.values().forEach(CountDownLatch::countDown);
        }

        void release(String paymentMethod) {
            gates.get(paymentMethod).countDown();
        }

        @Override
        public PaymentConfirmation charge(String paymentMethod, BigDecimal amount) {
            var gate = gates.get(paymentMethod);
            if (gate != null) {
                arrivals.get(paymentMethod).countDown();
                try {
                    if (!gate.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("charge for '" + paymentMethod + "' was never released by the test");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return new PaymentConfirmation("txn-" + paymentMethod);
        }
    }
}
