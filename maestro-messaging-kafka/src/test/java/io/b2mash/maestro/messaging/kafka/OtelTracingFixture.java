package io.b2mash.maestro.messaging.kafka;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * A real OpenTelemetry SDK behind Micrometer Tracing's {@link Tracer} and
 * {@link Propagator}, with a real W3C {@code traceparent} propagator.
 *
 * <p>The propagation contract test pins actual header names and grammar on an
 * actual broker, so a test double would prove nothing about the wire.
 *
 * <p><b>Thread safety:</b> the tracer and propagator are thread-safe; the
 * fixture itself is created per test.
 */
final class OtelTracingFixture implements AutoCloseable {

    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
    private final Tracer tracer;
    private final Propagator propagator;

    OtelTracingFixture() {
        var otelTracer = tracerProvider.get("maestro-kafka-test");
        this.tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
        this.propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);
    }

    Tracer tracer() {
        return tracer;
    }

    Propagator propagator() {
        return propagator;
    }

    @Override
    public void close() {
        tracerProvider.close();
    }
}
