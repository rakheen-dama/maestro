package io.b2mash.maestro.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@link WorkflowMessaging} SPI surface. Maestro starts workflows
 * in-process, so the SPI carries signals and lifecycle events only; a task
 * dispatch channel ({@code publishTask}/{@code subscribe}) was dead weight
 * every transport had to implement and must not creep back.
 */
@DisplayName("WorkflowMessaging exposes only signal and lifecycle-event methods")
class WorkflowMessagingSurfaceTest {

    @Test
    @DisplayName("declares exactly publishSignal, publishLifecycleEvent and subscribeSignals")
    void surfaceIsSignalsAndLifecycleEventsOnly() {
        assertEquals(
                Set.of("publishSignal", "publishLifecycleEvent", "subscribeSignals"),
                Arrays.stream(WorkflowMessaging.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(toSet()));
    }
}
