package io.b2mash.maestro.spring.observe;

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
 * A real OpenTelemetry SDK behind Micrometer Tracing's {@link Tracer} and
 * {@link Propagator}, plus an in-memory exporter.
 *
 * <p>Deliberately not {@code SimpleTracer} from {@code micrometer-tracing-test}
 * (the design doc's suggestion): the pins here are about <em>real</em> parent
 * edges, real 32-hex trace IDs and real W3C headers. A test double records what
 * the adapter asked for; this fixture records what a tracing backend would
 * actually receive, which is the thing the spec's evidence is about.
 *
 * <p><b>Thread safety:</b> the exporter's buffer is a
 * {@link CopyOnWriteArrayList} — spans arrive from whichever thread ended them.
 */
final class OtelTracingFixture implements AutoCloseable {

    private final SdkTracerProvider tracerProvider;
    private final CollectingExporter exporter = new CollectingExporter();
    private final Tracer tracer;
    private final Propagator propagator;

    OtelTracingFixture() {
        this.tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var otelTracer = tracerProvider.get("maestro-test");
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

    /** @return every span finished so far, in completion order */
    List<SpanData> finishedSpans() {
        return List.copyOf(exporter.spans);
    }

    /** @return the finished spans with the given name, in completion order */
    List<SpanData> spansNamed(String name) {
        return exporter.spans.stream().filter(s -> s.getName().equals(name)).toList();
    }

    /** @return the string attribute value on a span, or {@code null} */
    static @Nullable String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    void reset() {
        exporter.spans.clear();
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
