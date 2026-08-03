package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.observe.TraceContextHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.common.header.Headers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The Kafka side of Maestro's W3C trace-context contract (observability design
 * doc §4): injects the current span context into outbound record headers, and
 * restores it around each inbound handler call.
 *
 * <h2>Wire contract</h2>
 * Whatever the configured {@link Propagator} emits, written as lowercase ASCII
 * Kafka record headers. With Spring Boot's default
 * {@code management.tracing.propagation.type=W3C} that is:
 * <ul>
 *   <li>{@code traceparent} —
 *       {@code 00-{32 hex trace-id}-{16 hex span-id}-{2 hex flags}}, present
 *       whenever a span is active at publish time;</li>
 *   <li>{@code tracestate} — only when non-empty;</li>
 *   <li>{@code baggage} — only when the propagator emits it.</li>
 * </ul>
 * With no active span, nothing is written: an untraced publish must never
 * fabricate a trace context, and a build with no tracing beans produces a
 * byte-identical wire format to one from before tracing existed.
 *
 * <h2>Two hops out of the listener thread</h2>
 * {@link #runWithExtractedContext} starts a short {@code maestro.signal.receive}
 * consumer span under the remote parent and puts it in scope, <em>and</em> sets
 * {@link TraceContextHolder} to the raw {@code traceparent} for the duration of
 * the handler. The scope serves in-process instrumentation; the holder is what
 * {@code SignalManager.deliverSignal} reads to persist the context on the signal
 * row, which is the hop that survives a park, a crash and a different node.
 *
 * <p>The holder is always restored on exit — including when the handler throws,
 * which it must be free to do so the container's error handler can redeliver
 * rather than acknowledge an unprocessed record. Without that discipline the
 * next, unrelated record on the same pooled listener thread would inherit a
 * stale trace context and be filed under someone else's trace.
 *
 * <h2>Thread safety</h2>
 * Stateless beyond the two immutable collaborators; safe to share across every
 * listener container and every publishing thread.
 */
public final class KafkaTracePropagation {

    private static final Logger logger = LoggerFactory.getLogger(KafkaTracePropagation.class);

    /** Design §4.1: the header whose value is stored on the signal row. */
    static final String TRACEPARENT_HEADER = "traceparent";

    private static final String RECEIVE_SPAN = "maestro.signal.receive";

    /**
     * The W3C {@code traceparent} grammar, pinned to version {@code 00} exactly
     * as design §4.1 specifies.
     *
     * <p><b>Why validation lives here, at extraction.</b> The extracted value
     * does not stay in memory: it goes into {@code TraceContextHolder}, and from
     * there {@code SignalManager.deliverSignal} persists it verbatim on the
     * signal row (a {@code VARCHAR(128)} column). A hostile or merely buggy
     * producer on {@code maestro.signals.&#123;service&#125;} could therefore send an
     * over-long or malformed header and make {@code saveSignal} fail — and
     * because {@code deliverSignal} runs inside the listener, that failure
     * propagates, the record is never acked, redelivery exhausts its budget and
     * the record is dead-lettered. A signal would be <em>discarded</em>, breaking
     * the engine's "never discard a signal" invariant, and a trace header —
     * decorative metadata — would have caused it.
     *
     * <p>So an inbound value that is not exactly grammar-valid is treated as
     * <b>absent</b>: no span, no holder, nothing persisted, signal delivered
     * normally. That also satisfies RULING 2's requirement that a bad or missing
     * trace context degrade rather than error. Grammar-valid implies 55
     * characters, so this subsumes any length cap on the column.
     */
    private static final Pattern TRACEPARENT_GRAMMAR =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * @param tracer     the Micrometer tracer supplying the current span at
     *                   publish time and the scope at consume time
     * @param propagator the Micrometer propagator that owns the wire format
     */
    public KafkaTracePropagation(Tracer tracer, Propagator propagator) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.propagator = Objects.requireNonNull(propagator, "propagator must not be null");
    }

    /**
     * Injects the current span context into the record headers. A no-op when no
     * span is active.
     *
     * @param headers the outbound record's headers
     */
    public void inject(Headers headers) {
        var span = tracer.currentSpan();
        if (span == null) {
            return;
        }
        propagator.inject(span.context(), headers, KafkaTracePropagation::setHeader);
    }

    /**
     * @param headers the inbound record's headers
     * @return the raw {@code traceparent} header value, or {@code null} when the
     *         record carries none <em>or carries one that is not grammar-valid</em>
     *         — see {@link #TRACEPARENT_GRAMMAR} for why an invalid value must
     *         never be allowed to travel further
     */
    public @Nullable String extractTraceparent(Headers headers) {
        var raw = getHeader(headers, TRACEPARENT_HEADER);
        if (raw == null) {
            return null;
        }
        if (!TRACEPARENT_GRAMMAR.matcher(raw).matches()) {
            logger.debug("Ignoring malformed inbound traceparent header ({} chars) — "
                    + "the signal is delivered untraced rather than rejected", raw.length());
            return null;
        }
        return raw;
    }

    /**
     * Runs {@code action} with the record's remote trace context current and
     * {@link TraceContextHolder} set to its raw {@code traceparent}.
     *
     * <p>A record with no {@code traceparent} is run with the holder explicitly
     * cleared — absence degrades to an untraced delivery, never to an error, and
     * never to inheriting the previous record's context.
     *
     * @param headers the inbound record's headers
     * @param action  the handler to run
     */
    public void runWithExtractedContext(Headers headers, Runnable action) {
        var traceparent = extractTraceparent(headers);
        if (traceparent == null) {
            TraceContextHolder.runWith(null, action);
            return;
        }
        var span = propagator.extract(headers, KafkaTracePropagation::getHeader)
                .name(RECEIVE_SPAN)
                .kind(Span.Kind.CONSUMER)
                .start();
        try (var ignored = tracer.withSpan(span)) {
            TraceContextHolder.runWith(traceparent, action);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static void setHeader(@Nullable Headers headers, String key, @Nullable String value) {
        if (headers == null || value == null) {
            return;
        }
        headers.remove(key);
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable String getHeader(@Nullable Headers headers, String key) {
        if (headers == null) {
            return null;
        }
        var header = headers.lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
