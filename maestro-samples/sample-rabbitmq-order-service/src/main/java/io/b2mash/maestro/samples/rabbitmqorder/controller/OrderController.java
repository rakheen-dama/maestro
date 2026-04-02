package io.b2mash.maestro.samples.rabbitmqorder.controller;

import io.b2mash.maestro.samples.rabbitmqorder.domain.OrderInput;
import io.b2mash.maestro.samples.rabbitmqorder.domain.OrderResponse;
import io.b2mash.maestro.samples.rabbitmqorder.domain.OrderStatus;
import io.b2mash.maestro.samples.rabbitmqorder.domain.PlaceOrderRequest;
import io.b2mash.maestro.samples.rabbitmqorder.workflow.OrderFulfilmentWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class OrderController {

    private final MaestroClient maestro;

    public OrderController(MaestroClient maestro) {
        this.maestro = maestro;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody PlaceOrderRequest request) {
        var orderId = UUID.randomUUID().toString();

        var input = new OrderInput(
            orderId,
            request.customerId(),
            request.items(),
            request.paymentMethod(),
            request.shippingAddress()
        );

        maestro.newWorkflow(OrderFulfilmentWorkflow.class,
            WorkflowOptions.builder()
                .workflowId("order-" + orderId)
                .build()
        ).startAsync(input);

        return ResponseEntity.accepted().body(new OrderResponse(orderId));
    }

    @GetMapping("/orders/{orderId}/status")
    public OrderStatus getStatus(@PathVariable String orderId) {
        return maestro.getWorkflow("order-" + orderId)
            .query("getStatus", OrderStatus.class);
    }
}
