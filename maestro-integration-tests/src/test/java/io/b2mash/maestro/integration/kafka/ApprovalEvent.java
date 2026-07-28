package io.b2mash.maestro.integration.kafka;

/**
 * The business event the P1 suites publish to a domain topic — the thing a real
 * service would emit and a {@code @MaestroSignalListener} would route.
 *
 * <p>It carries the workflow ID directly, which a production router would
 * normally derive (e.g. {@code "order-" + event.orderId()}); the derivation is
 * not what these suites are proving.
 *
 * <p><b>Thread safety:</b> immutable record.
 *
 * @param workflowId the workflow the routed signal should reach
 * @param decision   the payload the workflow receives as its signal
 */
public record ApprovalEvent(String workflowId, String decision) {}
