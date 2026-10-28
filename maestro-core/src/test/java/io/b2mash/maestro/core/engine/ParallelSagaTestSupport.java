package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.saga.CompensationStack;
import io.b2mash.maestro.core.saga.SagaManager;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Shared harness for tests that drive {@code parallel()} and
 * {@link SagaManager#compensate} together against in-memory fakes.
 *
 * <p>Wiring mirrors {@code WorkflowVersionParallelBranchTest}, plus a
 * {@link SagaManager}. The store enforces {@code (instance, seq)} uniqueness.
 *
 * <p><b>Thread safety:</b> not thread-safe; one instance per test method.
 */
abstract class ParallelSagaTestSupport {

    VersionedInMemoryStore store;
    PayloadSerializer serializer;
    WorkflowInstance instance;
    CompensationStack stack;
    DefaultWorkflowOperations ops;
    SagaManager sagaManager;
    WorkflowContext ctx;

    @BeforeEach
    void setUpSagaSupport() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        var now = Instant.now();
        instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("parallel-saga-wf")
                .runId(UUID.randomUUID())
                .workflowType("ParallelSagaWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
        store.createInstance(instance);

        var parkingLot = new ParkingLot();
        var signalManager = new SignalManager(store, null, null, serializer, parkingLot);
        stack = new CompensationStack();
        ops = new DefaultWorkflowOperations(store, null, null, serializer, parkingLot,
                stack, signalManager);
        sagaManager = new SagaManager(store, null, serializer, "test-service");
        ctx = newContext(0, true, ops);
    }

    /** Builds a context on {@code instance}'s identity. */
    WorkflowContext newContext(int initialSequence, boolean replaying, WorkflowOperations operations) {
        return new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), "test-service",
                initialSequence, replaying, operations);
    }

    /** Runs {@code body} with {@link #ctx} bound as the current workflow context. */
    void inScope(Runnable body) {
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(body);
    }

    /** @return every event as {@code seq:TYPE:stepName}, sorted by sequence number */
    List<String> eventLogSortedBySequence() {
        return store.getEvents(instance.id()).stream()
                .sorted(Comparator.comparingLong(e -> (long) e.sequenceNumber()))
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }

    long count(EventType type) {
        return store.getEvents(instance.id()).stream().filter(e -> e.eventType() == type).count();
    }

    long count(EventType type, String stepName) {
        return store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == type && stepName.equals(e.stepName())).count();
    }
}
