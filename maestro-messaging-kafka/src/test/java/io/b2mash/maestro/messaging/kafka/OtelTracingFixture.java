package io.b2mash.maestro.messaging.kafka;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real OpenTelemetry SDK behind Micrometer Tracing's {@link Tracer} and
 * {@link Propagator}, with a real W3C {@code traceparent} propagator.
 *
 * <p>The propagation contract test pins actual header names and grammar on an
 * actual broker, so a test double would prove nothing about the wire.
 *
 * <p>Finished spans are collected in memory ({@link #spansNamed}), so a test
 * can compare a header against the span a tracing backend would receive.
 *
 * <p><b>Thread safety:</b> the tracer and propagator are thread-safe; the
 * exporter buffer is a {@link CopyOnWriteArrayList}; the fixture itself is
 * created per test.
 */
final class OtelTracingFixture implements AutoCloseable {

    private final CollectingExporter exporter = new CollectingExporter();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
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

    /** @return the finished spans with the given name, in completion order */
    List<SpanData> spansNamed(String name) {
        return exporter.spans.stream().filter(s -> s.getName().equals(name)).toList();
    }

    @Override
    public void close() {
        tracerProvider.close();
    }

    private static final class CollectingExporter implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
