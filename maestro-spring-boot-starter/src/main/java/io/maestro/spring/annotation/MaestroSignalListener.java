package io.maestro.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Routes messages from an external Kafka topic to a workflow signal.
 *
 * <p>Annotated methods act as signal routers: when a message arrives on the
 * specified Kafka topic, the framework deserializes it to the method's
 * parameter type, invokes the method, and delivers the resulting
 * {@link SignalRouting} as a workflow signal via the engine.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @MaestroSignalListener(topic = "payments.results", signalName = "payment.result")
 * public SignalRouting routePaymentResult(PaymentResultEvent event) {
 *     return SignalRouting.builder()
 *         .workflowId("order-" + event.orderId())
 *         .payload(new PaymentResult(event.success(), event.transactionId()))
 *         .build();
 * }
 * }</pre>
 *
 * <h2>Method Contract</h2>
 * <ul>
 *   <li>Must have exactly <b>one parameter</b> — the deserialized Kafka message.</li>
 *   <li>Must return a <b>non-null</b> {@link SignalRouting}.</li>
 *   <li>Must be {@code public}.</li>
 * </ul>
 *
 * <h2>Consumer Group</h2>
 * <p>The consumer group defaults to {@code maestro-{serviceName}}. Use
 * {@link #groupIdSuffix()} to append a suffix for listeners that need
 * independent offset tracking.
 *
 * @see SignalRouting
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MaestroSignalListener {

    /**
     * The Kafka topic to consume messages from.
     *
     * <p>Topics must be pre-created — Maestro does not auto-create topics.
     *
     * @return the Kafka topic name
     */
    String topic();

    /**
     * The signal name to deliver to the target workflow.
     *
     * <p>This is the name the workflow uses in
     * {@code workflow.awaitSignal("payment.result", ...)}.
     *
     * @return the signal name
     */
    String signalName();

    /**
     * Optional suffix appended to the base consumer group.
     *
     * <p>When set, the consumer group becomes
     * {@code maestro-{serviceName}-{groupIdSuffix}}. Useful when multiple
     * listeners on different topics need independent offset tracking.
     *
     * @return the consumer group suffix, or empty to use the base group
     */
    String groupIdSuffix() default "";
}
