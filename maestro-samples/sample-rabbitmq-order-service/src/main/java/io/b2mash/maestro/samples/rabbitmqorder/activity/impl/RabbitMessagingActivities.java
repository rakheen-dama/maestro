package io.b2mash.maestro.samples.rabbitmqorder.activity.impl;

import io.b2mash.maestro.samples.rabbitmqorder.activity.MessagingActivities;
import io.b2mash.maestro.samples.rabbitmqorder.domain.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RabbitMessagingActivities implements MessagingActivities {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessagingActivities.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RabbitMessagingActivities(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPaymentRequest(PaymentRequest request) {
        try {
            var bytes = objectMapper.writeValueAsBytes(request);
            rabbitTemplate.send("payments.requests", "", new Message(bytes));
            log.info("Published payment request for order '{}' via RabbitMQ", request.orderId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish payment request for order: " + request.orderId(), e);
        }
    }
}
