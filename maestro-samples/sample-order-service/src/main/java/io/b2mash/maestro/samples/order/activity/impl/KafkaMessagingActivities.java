package io.b2mash.maestro.samples.order.activity.impl;

import io.b2mash.maestro.samples.order.activity.MessagingActivities;
import io.b2mash.maestro.samples.order.domain.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class KafkaMessagingActivities implements MessagingActivities {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingActivities.class);

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
            kafkaTemplate.send("payments.requests", request.orderId(), bytes).get();
            log.info("Published payment request for order: {}", request.orderId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish payment request for order: " + request.orderId(), e);
        }
    }
}
