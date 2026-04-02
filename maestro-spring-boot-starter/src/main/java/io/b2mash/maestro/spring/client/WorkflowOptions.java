package io.b2mash.maestro.spring.client;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Options for starting a new workflow execution.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var options = WorkflowOptions.builder()
 *     .workflowId("order-" + orderId)
 *     .build();
 * maestro.newWorkflow(OrderWorkflow.class, options).startAsync(input);
 * }</pre>
 *
 * @param workflowId the business workflow ID (e.g., {@code "order-abc"})
 */
public record WorkflowOptions(
        String workflowId
) {

    public WorkflowOptions {
        Objects.requireNonNull(workflowId, "workflowId is required");
        if (workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link WorkflowOptions}.
     */
    public static final class Builder {

        private @Nullable String workflowId;

        private Builder() {}

        /**
         * Sets the business workflow ID.
         *
         * @param workflowId unique identifier for this workflow execution
         * @return this builder
         */
        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public WorkflowOptions build() {
            return new WorkflowOptions(
                    Objects.requireNonNull(workflowId, "workflowId is required")
            );
        }
    }
}
