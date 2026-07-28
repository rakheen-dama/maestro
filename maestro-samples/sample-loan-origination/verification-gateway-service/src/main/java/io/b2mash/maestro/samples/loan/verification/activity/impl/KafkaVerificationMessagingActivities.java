package io.b2mash.maestro.samples.loan.verification.activity.impl;

import io.b2mash.maestro.samples.loan.verification.activity.VerificationMessagingActivities;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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
            kafkaTemplate.send(TOPIC, result.loanId(), bytes).get();
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
