package io.b2mash.maestro.samples.loan.underwriting.activity.impl;

import io.b2mash.maestro.samples.loan.underwriting.activity.DecisionMessagingActivities;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.underwriting.queue.PendingReviewRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes underwriting decisions to the {@code loans.underwriting.decisions}
 * Kafka topic (pre-created by the docker-compose init container), keyed by
 * loan ID so all rounds of one loan stay in partition order.
 */
@Component
public class KafkaDecisionMessagingActivities implements DecisionMessagingActivities {

    private static final Logger logger = LoggerFactory.getLogger(KafkaDecisionMessagingActivities.class);
    private static final String TOPIC = "loans.underwriting.decisions";
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PendingReviewRegistry registry;

    public KafkaDecisionMessagingActivities(KafkaTemplate<String, byte[]> kafkaTemplate,
                                            ObjectMapper objectMapper,
                                            PendingReviewRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public void publishDecision(UnderwritingDecision decision) {
        try {
            var bytes = objectMapper.writeValueAsBytes(decision);
            // Bounded wait: an unresponsive broker must fail the activity (and
            // enter the durable retry policy) instead of pinning the thread.
            kafkaTemplate.send(TOPIC, decision.loanId(), bytes)
                    .get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted while publishing decision for loan " + decision.loanId(), e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to publish decision for loan " + decision.loanId(), e);
        }
        registry.resolved(decision.loanId(), decision.round());
        logger.info("Published underwriting decision for loan {} round {}: {}",
                decision.loanId(), decision.round(), decision.verdict());
    }
}
