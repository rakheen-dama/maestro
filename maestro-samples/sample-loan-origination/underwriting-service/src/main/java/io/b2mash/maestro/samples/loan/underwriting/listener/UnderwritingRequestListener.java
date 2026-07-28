package io.b2mash.maestro.samples.loan.underwriting.listener;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.samples.loan.underwriting.domain.UnderwritingRequest;
import io.b2mash.maestro.samples.loan.underwriting.workflow.UnderwritingWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts one {@link UnderwritingWorkflow} per underwriting request consumed
 * from {@code loans.underwriting.requests}.
 *
 * <p>The workflow ID {@code underwriting-{loanId}-round{n}} (round taken
 * from the request payload) makes the start idempotent: Kafka redeliveries
 * and duplicate requests for the same round hit
 * {@link WorkflowAlreadyExistsException} and are ignored.
 */
@Component
public class UnderwritingRequestListener {

    private static final Logger logger = LoggerFactory.getLogger(UnderwritingRequestListener.class);

    private final MaestroClient maestro;
    private final ObjectMapper objectMapper;

    public UnderwritingRequestListener(MaestroClient maestro, ObjectMapper objectMapper) {
        this.maestro = maestro;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "loans.underwriting.requests", groupId = "underwriting")
    public void onUnderwritingRequest(byte[] message) {
        UnderwritingRequest request;
        try {
            request = objectMapper.readValue(message, UnderwritingRequest.class);
        } catch (Exception e) {
            logger.error("Discarding malformed underwriting request: {}", e.getMessage(), e);
            return;
        }

        var workflowId = "underwriting-%s-round%d".formatted(request.loanId(), request.round());
        try {
            maestro.newWorkflow(UnderwritingWorkflow.class,
                    WorkflowOptions.builder()
                            .workflowId(workflowId)
                            .build()
            ).startAsync(request);
            // Amount/income are sensitive financial data — keep them out of logs.
            logger.info("Started underwriting workflow '{}'", workflowId);
        } catch (WorkflowAlreadyExistsException e) {
            // Idempotent start: duplicate request or Kafka redelivery.
            logger.info("Underwriting workflow '{}' already exists — ignoring duplicate request",
                    workflowId);
        } catch (Exception e) {
            // Rethrow so the Kafka listener error handler retries (or routes
            // to DLT if configured) instead of acknowledging a failed start.
            logger.error("Failed to start underwriting workflow '{}': {}",
                    workflowId, e.getMessage(), e);
            throw e;
        }
    }
}
