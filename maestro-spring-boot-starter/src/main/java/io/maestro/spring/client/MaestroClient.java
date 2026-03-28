package io.maestro.spring.client;

import io.maestro.core.engine.PayloadSerializer;
import io.maestro.core.engine.WorkflowExecutor;
import io.maestro.core.spi.WorkflowStore;
import io.maestro.spring.config.WorkflowRegistrar;

/**
 * User-facing API for starting workflows, sending signals, and querying state.
 *
 * <p>Inject this bean into your services to interact with the Maestro engine:
 *
 * <pre>{@code
 * @Service
 * public class OrderService {
 *
 *     private final MaestroClient maestro;
 *
 *     public OrderService(MaestroClient maestro) {
 *         this.maestro = maestro;
 *     }
 *
 *     public UUID placeOrder(OrderInput input) {
 *         var options = WorkflowOptions.builder()
 *             .workflowId("order-" + input.orderId())
 *             .build();
 *         return maestro.newWorkflow(OrderFulfilmentWorkflow.class, options)
 *             .startAsync(input);
 *     }
 *
 *     public void confirmPayment(String orderId, PaymentResult result) {
 *         maestro.getWorkflow("order-" + orderId)
 *             .signal("payment.result", result);
 *     }
 *
 *     public String getOrderStatus(String orderId) {
 *         return maestro.getWorkflow("order-" + orderId)
 *             .query("currentStep", String.class);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Thread safety:</b> This class is immutable and safe for concurrent use.
 */
public final class MaestroClient {

    private final WorkflowExecutor executor;
    private final WorkflowRegistrar registrar;
    private final PayloadSerializer serializer;
    private final WorkflowStore store;

    public MaestroClient(
            WorkflowExecutor executor,
            WorkflowRegistrar registrar,
            PayloadSerializer serializer,
            WorkflowStore store
    ) {
        this.executor = executor;
        this.registrar = registrar;
        this.serializer = serializer;
        this.store = store;
    }

    /**
     * Creates a new workflow stub for starting a workflow execution.
     *
     * @param workflowClass the {@link io.maestro.core.annotation.DurableWorkflow}-annotated class
     * @param options        workflow options (must include workflow ID)
     * @param <T>            the workflow type
     * @return a stub for starting the workflow
     */
    public <T> WorkflowStub<T> newWorkflow(Class<T> workflowClass, WorkflowOptions options) {
        var registration = registrar.getRegistration(workflowClass);
        return new WorkflowStub<>(executor, registration, options, serializer, store);
    }

    /**
     * Gets a handle to an existing workflow for signal delivery and queries.
     *
     * @param workflowId the workflow's business ID
     * @return a handle for interacting with the workflow
     */
    public WorkflowHandle getWorkflow(String workflowId) {
        return new WorkflowHandle(executor, workflowId);
    }
}
