package io.maestro.spring.annotation;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Specifies how an external Kafka event maps to a workflow signal.
 *
 * <p>Returned by methods annotated with {@link MaestroSignalListener} to
 * tell the framework which workflow should receive the signal and what
 * payload to deliver.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * return SignalRouting.builder()
 *     .workflowId("order-" + event.orderId())
 *     .payload(new PaymentResult(event.success(), event.transactionId()))
 *     .build();
 * }</pre>
 *
 * <p><b>Thread safety:</b> This record is immutable and therefore thread-safe.
 *
 * @param workflowId the business workflow ID to deliver the signal to
 * @param payload    the signal payload object, or {@code null}
 * @see MaestroSignalListener
 */
public record SignalRouting(
        String workflowId,
        @Nullable Object payload
) {

    /**
     * Canonical constructor — enforces non-null workflowId.
     */
    public SignalRouting {
        Objects.requireNonNull(workflowId, "workflowId");
    }

    /**
     * Creates a new builder for {@link SignalRouting}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link SignalRouting}.
     */
    public static final class Builder {

        private @Nullable String workflowId;
        private @Nullable Object payload;

        private Builder() {}

        /**
         * Sets the target workflow's business ID.
         *
         * @param workflowId the workflow ID (required, must not be null)
         * @return this builder
         */
        public Builder workflowId(String workflowId) {
            this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
            return this;
        }

        /**
         * Sets the signal payload.
         *
         * <p>The payload is serialized by the framework using Jackson before
         * delivery. It can be any object that Jackson can serialize.
         *
         * @param payload the payload object, or {@code null}
         * @return this builder
         */
        public Builder payload(@Nullable Object payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Builds the {@link SignalRouting} instance.
         *
         * @return a new SignalRouting
         * @throws NullPointerException if {@code workflowId} was not set
         */
        public SignalRouting build() {
            Objects.requireNonNull(workflowId, "workflowId is required");
            return new SignalRouting(workflowId, payload);
        }
    }
}
