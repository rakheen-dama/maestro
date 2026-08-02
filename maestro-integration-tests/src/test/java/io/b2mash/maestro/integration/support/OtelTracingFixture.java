package io.b2mash.maestro.integration.support;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One node's tracing stack: a real OpenTelemetry SDK behind Micrometer
 * Tracing's {@link Tracer} and {@link Propagator}, with an in-memory exporter.
 *
 * <p>One fixture models one <em>service</em>. A cross-service assertion builds
 * two, exactly as two deployed services would each have their own SDK, and the
 * fact that a span exported by one carries the other's trace ID is then real
 * evidence of propagation rather than an artefact of sharing a tracer.
 *
 * <p><b>Thread safety:</b> the exporter buffer is a
 * {@link CopyOnWriteArrayList}; spans arrive from workflow virtual threads and
 * Kafka listener threads.
 */
public final class OtelTracingFixture implements AutoCloseable {

    private final SdkTracerProvider tracerProvider;
    private final CollectingExporter exporter = new CollectingExporter();
    private final Tracer tracer;
    private final Propagator propagator;

    /** @param serviceName the instrumentation-scope name, for readable dumps */
    public OtelTracingFixture(String serviceName) {
        this.tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var otelTracer = tracerProvider.get(serviceName);
        this.tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
        this.propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);
    }

    /** @return the Micrometer tracer this node instruments with */
    public Tracer tracer() {
        return tracer;
    }

    /** @return the Micrometer propagator this node reads/writes the wire with */
    public Propagator propagator() {
        return propagator;
    }

    /** @return every span this node finished, in completion order */
    public List<SpanData> finishedSpans() {
        return List.copyOf(exporter.spans);
    }

    /**
     * @param name the span name
     * @return this node's finished spans with that name, in completion order
     */
    public List<SpanData> spansNamed(String name) {
        return exporter.spans.stream().filter(s -> s.getName().equals(name)).toList();
    }

    /**
     * @param span the span to read
     * @param key  the attribute key
     * @return the string attribute value, or {@code null}
     */
    public static @Nullable String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
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
