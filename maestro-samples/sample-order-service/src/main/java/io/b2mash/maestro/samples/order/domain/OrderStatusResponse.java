package io.b2mash.maestro.samples.order.domain;

import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;

/**
 * What {@code GET /orders/{orderId}/status} returns.
 *
 * @param orderId       the order's ID
 * @param status        the workflow's durable status — readable from any node
 * @param step          the step the workflow reports through its
 *                      {@code getStatus} query, or {@code null} when this node
 *                      is not running it (another node owns it, or it has ended)
 * @param failureReason why the order failed when {@code status} is
 *                      {@code FAILED}, otherwise {@code null}
 */
public record OrderStatusResponse(
    String orderId,
    WorkflowStatus status,
    @Nullable String step,
    @Nullable String failureReason
) {}
