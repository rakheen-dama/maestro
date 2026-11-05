package io.b2mash.maestro.samples.order.controller;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowFailureDetail;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.order.domain.OrderStatusResponse;
import io.b2mash.maestro.samples.order.exception.OrderFailedException;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.config.WorkflowRegistrar;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OrderController#getStatus}: store-backed status and failure, the
 * query-backed step only where this node runs the order, and 404 for an
 * unknown order.
 */
class OrderControllerTest {

    private final InMemoryWorkflowStore store = new InMemoryWorkflowStore();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private WorkflowExecutor executor;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        executor = new WorkflowExecutor(store, null, null, null, serializer, "order-service");
        controller = new OrderController(
                new MaestroClient(executor, new WorkflowRegistrar(executor), serializer, store));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void anUnknownOrderIsNotFound() {
        var response = controller.getStatus("no-such-order");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void aFailedOrderReportsItsReason_andNoStepOffTheOwningNode() {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("order-o-1")
                .runId(UUID.randomUUID())
                .workflowType("order-fulfilment")
                .taskQueue("orders")
                .status(WorkflowStatus.FAILED)
                .output(serializer.serialize(new WorkflowFailureDetail(
                        OrderFailedException.class.getName(), "Payment failed: Card declined")))
                .serviceName("order-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());

        var response = controller.getStatus("o-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new OrderStatusResponse("o-1", WorkflowStatus.FAILED, null, "Payment failed: Card declined"),
                response.getBody());
    }
}
