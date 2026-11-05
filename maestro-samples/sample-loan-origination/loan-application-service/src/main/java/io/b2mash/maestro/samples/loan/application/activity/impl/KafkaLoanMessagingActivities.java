package io.b2mash.maestro.samples.loan.application.activity.impl;

import io.b2mash.maestro.messaging.kafka.MaestroSignalHeaders;
import io.b2mash.maestro.messaging.kafka.SignalMessageIds;
import io.b2mash.maestro.samples.loan.application.activity.LoanMessagingActivities;
import org.apache.kafka.clients.producer.ProducerRecord;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingRequested;
import io.b2mash.maestro.samples.loan.application.domain.VerificationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes cross-service events to the pre-created loan topics, keyed by
 * {@code loanId} per the SPEC contract.
 */
@Component
public class KafkaLoanMessagingActivities implements LoanMessagingActivities {

    private static final Logger logger = LoggerFactory.getLogger(KafkaLoanMessagingActivities.class);

    static final String VERIFICATION_REQUESTS_TOPIC = "loans.verification.requests";
    static final String UNDERWRITING_REQUESTS_TOPIC = "loans.underwriting.requests";
    // Below the PT10S startToCloseTimeout of the stub that calls this activity,
    // so a slow broker fails the send with its own error and the stub's retry
    // policy takes over, instead of the engine timing the attempt out mid-send.
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(8);

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
            // One activity, several events: each needs its own stable suffix.
            send(VERIFICATION_REQUESTS_TOPIC, application.applicationId(), event,
                    SignalMessageIds.forCurrentActivity(String.valueOf(type)));
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
        send(UNDERWRITING_REQUESTS_TOPIC, application.applicationId(), event,
                SignalMessageIds.forCurrentActivity());
        logger.info("Requested underwriting round {} for loan {}", round, application.applicationId());
    }

    /**
     * Sends one event with a {@code maestro-message-id} header: the same id on
     * every retry of the activity, so a receiving {@code @MaestroSignalListener}
     * keeps one signal however often the send is repeated.
     */
    private void send(String topic, String key, Object event, String messageId) {
        try {
            var bytes = objectMapper.writeValueAsBytes(event);
            var record = new ProducerRecord<String, byte[]>(topic, key, bytes);
            MaestroSignalHeaders.setMessageId(record.headers(), messageId);
            // Bounded wait: an unresponsive broker must fail the activity (and
            // enter the durable retry policy) instead of pinning the thread.
            kafkaTemplate.send(record).get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to " + topic, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to " + topic + " for loan " + key, e);
        }
    }
}
