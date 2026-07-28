package io.b2mash.maestro.samples.loan.application.activity.impl;

import io.b2mash.maestro.samples.loan.application.activity.LoanMessagingActivities;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingRequested;
import io.b2mash.maestro.samples.loan.application.domain.VerificationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes cross-service events to the pre-created loan topics, keyed by
 * {@code loanId} per the SPEC contract.
 */
@Component
public class KafkaLoanMessagingActivities implements LoanMessagingActivities {

    private static final Logger logger = LoggerFactory.getLogger(KafkaLoanMessagingActivities.class);

    static final String VERIFICATION_REQUESTS_TOPIC = "loans.verification.requests";
    static final String UNDERWRITING_REQUESTS_TOPIC = "loans.underwriting.requests";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaLoanMessagingActivities(KafkaTemplate<String, byte[]> kafkaTemplate,
                                        ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void requestVerifications(LoanApplication application) {
        for (var type : VERIFICATION_TYPES) {
            var event = new VerificationRequested(
                    application.applicationId(), type, application.amount());
            send(VERIFICATION_REQUESTS_TOPIC, application.applicationId(), event);
        }
        logger.info("Requested {} verifications for loan {}",
                VERIFICATION_TYPES.size(), application.applicationId());
    }

    @Override
    public void requestUnderwriting(LoanApplication application, int round) {
        var event = new UnderwritingRequested(
                application.applicationId(),
                round,
                application.amount(),
                application.income(),
                application.propertyValue(),
                true);
        send(UNDERWRITING_REQUESTS_TOPIC, application.applicationId(), event);
        logger.info("Requested underwriting round {} for loan {}", round, application.applicationId());
    }

    private void send(String topic, String key, Object event) {
        try {
            var bytes = objectMapper.writeValueAsBytes(event);
            kafkaTemplate.send(topic, key, bytes).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to " + topic, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to " + topic + " for loan " + key, e);
        }
    }
}
