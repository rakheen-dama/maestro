package io.b2mash.maestro.integration.observability;

/**
 * Issue 23 done-when (b): an inbound {@code traceparent} header on a
 * {@code @MaestroSignalListener} topic is persisted into the signal row's
 * {@code trace_context} — over a real broker, through the real
 * annotation-driven listener path ({@code MaestroSignalListenerBeanPostProcessor}),
 * onto the real Postgres column.
 *
 * <p>Skeleton — test body added in the next commit.
 */
class SignalListenerTraceContextIT {
}
