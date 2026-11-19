package io.b2mash.maestro.rollingnode;

import io.b2mash.maestro.rollingnode.workflow.RollingSignalWorkflow;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.springframework.stereotype.Component;

/**
 * Routes records of the domain topic {@value #TOPIC} to the {@code go} signal
 * of the workflow each record names: the Kafka signal path a service uses
 * between services ({@code @MaestroSignalListener}).
 *
 * <p>Active only on a node launched with Maestro's Kafka messaging (the test
 * excludes {@code KafkaMessagingAutoConfiguration} on every other node, and
 * then this bean is inert). Every node of a scenario uses the consumer group
 * the test passes as {@code maestro.messaging.consumer-group}, so nodes of
 * both builds share one group. The record's message id is derived by the
 * consuming build: the {@code maestro-message-id} header as
 * {@code hdr:<topic>:<id>}, else the record's coordinate as
 * {@code kafka:<topicId>:<partition>:<offset>}.
 *
 * <p><b>Thread safety:</b> stateless.
 */
@Component
public class RollingSignalListener {

    /** The domain topic, pre-created by the test with one partition. */
    public static final String TOPIC = "rolling.events";

    /**
     * One domain record: which workflow to signal, and the payload.
     *
     * @param workflowId the workflow to signal
     * @param payload    the {@code go} signal's payload
     */
    public record RollingEvent(String workflowId, String payload) {
    }

    /**
     * @param event the record's JSON value
     * @return the {@code go} signal for {@code event.workflowId()}
     */
    @MaestroSignalListener(topic = TOPIC, signalName = RollingSignalWorkflow.SIGNAL)
    public SignalRouting route(RollingEvent event) {
        return SignalRouting.builder().workflowId(event.workflowId()).payload(event.payload()).build();
    }
}
