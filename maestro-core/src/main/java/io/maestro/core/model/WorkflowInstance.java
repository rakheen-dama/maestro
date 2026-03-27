package io.maestro.core.model;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single execution of a durable workflow.
 *
 * <p>A workflow instance tracks the full lifecycle of a business process —
 * from creation through activity execution, signal handling, and eventual
 * completion or failure. State is persisted to Postgres via
 * {@link io.maestro.core.spi.WorkflowStore}.
 *
 * <p>Instances are immutable once built. State transitions produce new
 * instances via {@link #toBuilder()}:
 * <pre>{@code
 * var updated = instance.toBuilder()
 *     .status(WorkflowStatus.COMPLETED)
 *     .completedAt(Instant.now())
 *     .version(instance.version() + 1)
 *     .build();
 * }</pre>
 *
 * <p><b>Identity:</b> Two instances are equal if they share the same {@link #id()}.
 * Version differences do not affect equality.
 *
 * <p><b>Thread safety:</b> Instances are immutable and therefore thread-safe.
 * Concurrent modifications are guarded by the store's optimistic locking
 * on the {@link #version()} field.
 *
 * @see WorkflowStatus
 * @see io.maestro.core.spi.WorkflowStore
 */
@JsonDeserialize(builder = WorkflowInstance.Builder.class)
public final class WorkflowInstance {

    private final UUID id;
    private final String workflowId;
    private final UUID runId;
    private final String workflowType;
    private final String taskQueue;
    private final WorkflowStatus status;
    private final @Nullable JsonNode input;
    private final @Nullable JsonNode output;
    private final String serviceName;
    private final int eventSequence;
    private final Instant startedAt;
    private final @Nullable Instant completedAt;
    private final Instant updatedAt;
    private final int version;

    private WorkflowInstance(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.workflowId = Objects.requireNonNull(builder.workflowId, "workflowId");
        this.runId = Objects.requireNonNull(builder.runId, "runId");
        this.workflowType = Objects.requireNonNull(builder.workflowType, "workflowType");
        this.taskQueue = Objects.requireNonNull(builder.taskQueue, "taskQueue");
        this.status = Objects.requireNonNull(builder.status, "status");
        this.input = builder.input;
        this.output = builder.output;
        this.serviceName = Objects.requireNonNull(builder.serviceName, "serviceName");
        this.eventSequence = builder.eventSequence;
        this.startedAt = Objects.requireNonNull(builder.startedAt, "startedAt");
        this.completedAt = builder.completedAt;
        this.updatedAt = Objects.requireNonNull(builder.updatedAt, "updatedAt");
        this.version = builder.version;
    }

    /** Returns a new builder for constructing a {@code WorkflowInstance}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder pre-populated with this instance's values,
     * allowing selective field overrides for state transitions.
     */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .workflowId(workflowId)
                .runId(runId)
                .workflowType(workflowType)
                .taskQueue(taskQueue)
                .status(status)
                .input(input)
                .output(output)
                .serviceName(serviceName)
                .eventSequence(eventSequence)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .updatedAt(updatedAt)
                .version(version);
    }

    /** Primary key (UUID). */
    public UUID id() { return id; }

    /** Business workflow ID (e.g., {@code "order-abc"}). Unique across all instances. */
    public String workflowId() { return workflowId; }

    /** Run ID tracking the current execution attempt. Changes on manual retry. */
    public UUID runId() { return runId; }

    /** Workflow type name (e.g., {@code "order-fulfilment"}). */
    public String workflowType() { return workflowType; }

    /** Task queue this workflow is assigned to. */
    public String taskQueue() { return taskQueue; }

    /** Current lifecycle status. */
    public WorkflowStatus status() { return status; }

    /** Workflow input, serialized as JSON. May be {@code null} for no-input workflows. */
    public @Nullable JsonNode input() { return input; }

    /** Workflow output, serialized as JSON. Set when the workflow completes. */
    public @Nullable JsonNode output() { return output; }

    /** Name of the service that owns this workflow. */
    public String serviceName() { return serviceName; }

    /** Current event sequence counter. Incremented for each activity checkpoint. */
    public int eventSequence() { return eventSequence; }

    /** Timestamp when the workflow was first started. */
    public Instant startedAt() { return startedAt; }

    /** Timestamp when the workflow reached a terminal state. {@code null} while active. */
    public @Nullable Instant completedAt() { return completedAt; }

    /** Timestamp of the last state change. Used for staleness detection. */
    public Instant updatedAt() { return updatedAt; }

    /** Optimistic lock version. Incremented on every store update. */
    public int version() { return version; }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowInstance that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "WorkflowInstance{id=%s, workflowId='%s', status=%s, version=%d}"
                .formatted(id, workflowId, status, version);
    }

    /**
     * Builder for constructing {@link WorkflowInstance} objects.
     *
     * <p>Required fields: {@code id}, {@code workflowId}, {@code runId},
     * {@code workflowType}, {@code taskQueue}, {@code status},
     * {@code serviceName}, {@code startedAt}, {@code updatedAt}.
     *
     * <p>Defaults: {@code eventSequence = 0}, {@code version = 0}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private @Nullable UUID id;
        private @Nullable String workflowId;
        private @Nullable UUID runId;
        private @Nullable String workflowType;
        private @Nullable String taskQueue;
        private @Nullable WorkflowStatus status;
        private @Nullable JsonNode input;
        private @Nullable JsonNode output;
        private @Nullable String serviceName;
        private int eventSequence;
        private @Nullable Instant startedAt;
        private @Nullable Instant completedAt;
        private @Nullable Instant updatedAt;
        private int version;

        private Builder() {}

        /** Sets the primary key. Required. */
        public Builder id(UUID id) { this.id = id; return this; }

        /** Sets the business workflow ID. Required. */
        public Builder workflowId(String workflowId) { this.workflowId = workflowId; return this; }

        /** Sets the run ID. Required. */
        public Builder runId(UUID runId) { this.runId = runId; return this; }

        /** Sets the workflow type name. Required. */
        public Builder workflowType(String workflowType) { this.workflowType = workflowType; return this; }

        /** Sets the task queue. Required. */
        public Builder taskQueue(String taskQueue) { this.taskQueue = taskQueue; return this; }

        /** Sets the lifecycle status. Required. */
        public Builder status(WorkflowStatus status) { this.status = status; return this; }

        /** Sets the workflow input. Optional. */
        public Builder input(@Nullable JsonNode input) { this.input = input; return this; }

        /** Sets the workflow output. Optional. */
        public Builder output(@Nullable JsonNode output) { this.output = output; return this; }

        /** Sets the owning service name. Required. */
        public Builder serviceName(String serviceName) { this.serviceName = serviceName; return this; }

        /** Sets the event sequence counter. Defaults to {@code 0}. */
        public Builder eventSequence(int eventSequence) { this.eventSequence = eventSequence; return this; }

        /** Sets the start timestamp. Required. */
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }

        /** Sets the completion timestamp. Optional. */
        public Builder completedAt(@Nullable Instant completedAt) { this.completedAt = completedAt; return this; }

        /** Sets the last-updated timestamp. Required. */
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        /** Sets the optimistic lock version. Defaults to {@code 0}. */
        public Builder version(int version) { this.version = version; return this; }

        /**
         * Builds a new {@link WorkflowInstance}.
         *
         * @throws NullPointerException if any required field is {@code null}
         */
        public WorkflowInstance build() {
            return new WorkflowInstance(this);
        }
    }
}
