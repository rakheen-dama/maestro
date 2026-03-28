package io.maestro.samples.order.signal;

import io.maestro.spring.annotation.MaestroSignalListener;
import io.maestro.spring.annotation.SignalRouting;
import io.maestro.samples.order.domain.PaymentResult;
import io.maestro.samples.order.domain.PaymentResultEvent;
import org.springframework.stereotype.Component;

@Component
public class PaymentResultSignalRouter {

    @MaestroSignalListener(topic = "payments.results", signalName = "payment.result")
    public SignalRouting routePaymentResult(PaymentResultEvent event) {
        return SignalRouting.builder()
            .workflowId("order-" + event.orderId())
            .payload(new PaymentResult(event.success(), event.transactionId(), event.reason()))
            .build();
    }
}
