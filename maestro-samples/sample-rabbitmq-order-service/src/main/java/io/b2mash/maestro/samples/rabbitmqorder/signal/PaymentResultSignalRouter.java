package io.b2mash.maestro.samples.rabbitmqorder.signal;

import io.b2mash.maestro.samples.rabbitmqorder.domain.PaymentResult;
import io.b2mash.maestro.samples.rabbitmqorder.domain.PaymentResultEvent;
import io.b2mash.maestro.spring.client.MaestroClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentResultSignalRouter {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultSignalRouter.class);

    private final MaestroClient maestro;
    private final ObjectMapper objectMapper;

    public PaymentResultSignalRouter(MaestroClient maestro, ObjectMapper objectMapper) {
        this.maestro = maestro;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "payments.results")
    public void onPaymentResult(Message message) {
        try {
            var event = objectMapper.readValue(message.getBody(), PaymentResultEvent.class);
            maestro.getWorkflow("order-" + event.orderId())
                .signal("payment.result",
                    new PaymentResult(event.success(), event.transactionId(), event.reason()));
            log.info("Routed payment result for order '{}'", event.orderId());
        } catch (Exception e) {
            log.error("Failed to route payment result signal", e);
            throw new RuntimeException("Failed to route payment result signal", e);
        }
    }
}
