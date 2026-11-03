package io.b2mash.maestro.samples.payment.activity.impl;

import io.b2mash.maestro.samples.payment.activity.PaymentMessagingActivities;
import io.b2mash.maestro.samples.payment.domain.PaymentResult;
import io.b2mash.maestro.samples.payment.domain.PaymentResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes payment results to Kafka so that downstream services
 * (e.g. order service) can react to payment outcomes.
 */
@Component
public class KafkaPaymentMessagingActivities implements PaymentMessagingActivities {

    private static final Logger logger = LoggerFactory.getLogger(KafkaPaymentMessagingActivities.class);
    private static final String TOPIC = "payments.results";
    // Below the PT10S startToCloseTimeout of the stub that calls this activity,
    // so a slow broker fails the send with its own error and the stub's retry
    // policy takes over, instead of the engine timing the attempt out mid-send
    // (and an unbounded get() pinning the attempt thread past that timeout).
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(8);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaPaymentMessagingActivities(KafkaTemplate<String, byte[]> kafkaTemplate,
                                           ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPaymentResult(String orderId, PaymentResult result) {
        var event = result.success()
                ? PaymentResultEvent.success(orderId, result.transactionId())
                : PaymentResultEvent.failed(orderId, result.reason());

        try {
            var bytes = objectMapper.writeValueAsBytes(event);
            kafkaTemplate.send(TOPIC, orderId, bytes).get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            logger.info("Published payment result for order {}: success={}", orderId, result.success());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing payment result for order " + orderId, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish payment result for order " + orderId, e);
        }
    }
}
