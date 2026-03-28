package io.maestro.samples.payment.activity.impl;

import io.maestro.samples.payment.activity.PaymentMessagingActivities;
import io.maestro.samples.payment.domain.PaymentResult;
import io.maestro.samples.payment.domain.PaymentResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes payment results to Kafka so that downstream services
 * (e.g. order service) can react to payment outcomes.
 */
@Component
public class KafkaPaymentMessagingActivities implements PaymentMessagingActivities {

    private static final Logger logger = LoggerFactory.getLogger(KafkaPaymentMessagingActivities.class);
    private static final String TOPIC = "payments.results";

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
            kafkaTemplate.send(TOPIC, orderId, bytes).get();
            logger.info("Published payment result for order {}: success={}", orderId, result.success());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish payment result for order " + orderId, e);
        }
    }
}
