package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@code maestro.wake.permits} gauge registered by
 * {@link MaestroEngineGauges}: it exists under that name and reads
 * {@link WorkflowExecutor#wakePermitCount()}.
 */
class MaestroEngineGaugesTest {

    @Test
    void wakePermitsGaugeIsRegisteredAgainstTheExecutor() {
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                new PayloadSerializer(JsonMapper.builder().build()), "svc").build();
        try {
            var registry = new SimpleMeterRegistry();
            new MaestroEngineGauges(registry, executor);
            assertEquals(0.0, registry.get("maestro.wake.permits").gauge().value());
            assertEquals(executor.wakePermitCount(), (int) registry.get("maestro.wake.permits").gauge().value());
        } finally {
            executor.shutdown();
        }
    }
}
