package io.b2mash.maestro.samples.order.controller;

import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.exception.WorkflowNotQueryableException;
import io.b2mash.maestro.core.model.WorkflowFailureDetail;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.order.domain.OrderInput;
import io.b2mash.maestro.samples.order.domain.OrderResponse;
import io.b2mash.maestro.samples.order.domain.OrderStatus;
import io.b2mash.maestro.samples.order.domain.OrderStatusResponse;
import io.b2mash.maestro.samples.order.domain.PlaceOrderRequest;
import io.b2mash.maestro.samples.order.workflow.OrderFulfilmentWorkflow;
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

    /**
     * Reports an order's progress. The durable status and failure come from the
     * store, so any node can answer; the fine-grained step comes from the
     * workflow's {@code @QueryMethod}, which only the node running the workflow
     * can answer — elsewhere, or once the order has ended, it is {@code null}.
     * An unknown order is a 404.
     */
    @GetMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderStatusResponse> getStatus(@PathVariable String orderId) {
        var handle = maestro.getWorkflow("order-" + orderId);
        WorkflowStatus status;
        try {
            status = handle.status();
        } catch (WorkflowNotFoundException unknownOrder) {
            return ResponseEntity.notFound().build();
        }
        String step;
        try {
            step = handle.query("getStatus", OrderStatus.class).currentStep();
        } catch (WorkflowNotQueryableException notOnThisNode) {
            step = null;
        }
        var failureReason = handle.failure().map(WorkflowFailureDetail::message).orElse(null);
        return ResponseEntity.ok(new OrderStatusResponse(orderId, status, step, failureReason));
    }
}
