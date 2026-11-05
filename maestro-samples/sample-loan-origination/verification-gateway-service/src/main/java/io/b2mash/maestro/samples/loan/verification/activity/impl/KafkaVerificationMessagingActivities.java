package io.b2mash.maestro.samples.loan.verification.activity.impl;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.messaging.kafka.MaestroSignalHeaders;
import io.b2mash.maestro.messaging.kafka.SignalMessageIds;
import io.b2mash.maestro.samples.loan.verification.activity.VerificationMessagingActivities;
import org.apache.kafka.clients.producer.ProducerRecord;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes {@link VerificationResult} events to the pre-created
 * {@code loans.verification.results} Kafka topic, keyed by {@code loanId}.
 *
 * <p>The loan-application service consumes this topic and routes each event
 * into the {@code loan-{loanId}} workflow as a {@code verification.result}
 * signal.
 */
@Component
public class KafkaVerificationMessagingActivities implements VerificationMessagingActivities {

    private static final Logger logger =
            LoggerFactory.getLogger(KafkaVerificationMessagingActivities.class);
    private static final String TOPIC = "loans.verification.results";
    // Below the PT10S startToCloseTimeout of the stub that calls this activity,
    // so a slow broker fails the send with its own error and the stub's retry
    // policy takes over, instead of the engine timing the attempt out mid-send.
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(8);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaVerificationMessagingActivities(KafkaTemplate<String, byte[]> kafkaTemplate,
                                                ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishResult(VerificationResult result) {
        try {
            var bytes = objectMapper.writeValueAsBytes(result);
            var record = new ProducerRecord<String, byte[]>(TOPIC, result.loanId(), bytes);
            // From the workflow's activity: a stable id, so a retried send is
            // one signal at the loan service. The webhook controller calls this
            // as a plain method (no workflow bound) and sends no id.
            if (WorkflowContext.scopedValue().isBound()) {
                MaestroSignalHeaders.setMessageId(record.headers(), SignalMessageIds.forCurrentActivity());
            }
            // Bounded wait: an unresponsive broker must fail the activity (and
            // enter the durable retry policy) instead of pinning the thread.
            kafkaTemplate.send(record).get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            logger.info("Published {} verification result for loan {}: approved={}",
                    result.type(), result.loanId(), result.approved());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted publishing verification result for loan " + result.loanId(), e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to publish verification result for loan " + result.loanId(), e);
        }
    }
}
