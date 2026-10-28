package io.b2mash.maestro.samples.order.activity.impl;

import io.b2mash.maestro.samples.order.activity.MessagingActivities;
import io.b2mash.maestro.samples.order.domain.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaMessagingActivities implements MessagingActivities {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingActivities.class);
    // Below the PT10S startToCloseTimeout of the stub that calls this activity,
    // so a slow broker fails the send with its own error and the stub's retry
    // policy takes over, instead of the engine timing the attempt out mid-send
    // (and an unbounded get() pinning the attempt thread past that timeout).
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(8);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaMessagingActivities(KafkaTemplate<String, byte[]> kafkaTemplate,
                                    ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPaymentRequest(PaymentRequest request) {
        try {
            var bytes = objectMapper.writeValueAsBytes(request);
            kafkaTemplate.send("payments.requests", request.orderId(), bytes)
                    .get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            log.info("Published payment request for order: {}", request.orderId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing payment request for order: "
                    + request.orderId(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish payment request for order: " + request.orderId(), e);
        }
    }
}
