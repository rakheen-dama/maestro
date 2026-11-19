package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.engine.LifecycleRegistry;
import io.b2mash.maestro.core.engine.WorkflowExecutor;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked {@link WorkflowExecutor} whose {@code lifecycle()} admits every
 * member: since P1 task 5 the Kafka containers join the engine's lifecycle
 * registry before they start, and a bare mock's {@code lifecycle()} is
 * {@code null}. The membership's {@code leave()} is a no-op.
 */
public final class MockEngines {

    private MockEngines() {
    }

    /** @return a mocked executor with an admitting lifecycle registry */
    public static WorkflowExecutor executor() {
        var executor = mock(WorkflowExecutor.class);
        var registry = mock(LifecycleRegistry.class);
        var membership = mock(LifecycleRegistry.Membership.class);
        when(registry.join(any(), any())).thenReturn(Optional.of(membership));
        when(executor.lifecycle()).thenReturn(registry);
        return executor;
    }
}
