package io.b2mash.maestro.samples.payment.listener;

import io.b2mash.maestro.samples.payment.domain.PaymentRequest;
import io.b2mash.maestro.samples.payment.workflow.PaymentProcessingWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kafka consumer that triggers a new {@link PaymentProcessingWorkflow}
 * for each inbound payment request.
 */
@Component
public class PaymentRequestListener {

    private static final Logger logger = LoggerFactory.getLogger(PaymentRequestListener.class);

    private final MaestroClient maestro;
    private final ObjectMapper objectMapper;

    public PaymentRequestListener(MaestroClient maestro, ObjectMapper objectMapper) {
        this.maestro = maestro;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payments.requests", groupId = "payment-gateway")
    public void onPaymentRequest(byte[] message) {
        try {
            var request = objectMapper.readValue(message, PaymentRequest.class);
            logger.info("Received payment request for order {}: {} {}",
                    request.orderId(), request.paymentMethod(), request.amount());

            maestro.newWorkflow(PaymentProcessingWorkflow.class,
                    WorkflowOptions.builder()
                            .workflowId("payment-" + request.orderId())
                            .build()
            ).startAsync(request);

            logger.info("Started payment processing workflow for order {}", request.orderId());
        } catch (Exception e) {
            logger.error("Failed to process payment request: {}", e.getMessage(), e);
        }
    }
}
