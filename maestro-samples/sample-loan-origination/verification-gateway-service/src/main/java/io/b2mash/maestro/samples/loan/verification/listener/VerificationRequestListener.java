package io.b2mash.maestro.samples.loan.verification.listener;

import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.samples.loan.verification.config.VerificationGatewayProperties;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationRequest;
import io.b2mash.maestro.samples.loan.verification.domain.VerificationTask;
import io.b2mash.maestro.samples.loan.verification.workflow.VerificationWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code loans.verification.requests} and starts one
 * {@link VerificationWorkflow} per request with workflow id
 * {@code verification-{loanId}-{type}}.
 *
 * <p><b>Idempotent:</b> Kafka redeliveries and duplicate requests map to the
 * same workflow id; {@link WorkflowAlreadyExistsException} is caught and
 * logged, so at most one workflow runs per {@code (loanId, type)}.
 */
@Component
public class VerificationRequestListener {

    private static final Logger logger = LoggerFactory.getLogger(VerificationRequestListener.class);

    private final MaestroClient maestro;
    private final ObjectMapper objectMapper;
    private final VerificationGatewayProperties properties;

    public VerificationRequestListener(MaestroClient maestro,
                                       ObjectMapper objectMapper,
                                       VerificationGatewayProperties properties) {
        this.maestro = maestro;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @KafkaListener(topics = "loans.verification.requests", groupId = "verification-gateway")
    public void onVerificationRequest(byte[] message) {
        VerificationRequest request;
        try {
            request = objectMapper.readValue(message, VerificationRequest.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize verification request: {}", e.getMessage(), e);
            return;
        }

        var workflowId = "verification-%s-%s".formatted(request.loanId(), request.type());
        try {
            var latency = properties.latencyFor(request.type());
            var task = new VerificationTask(
                    request.loanId(), request.type(), request.amount(), latency.toMillis());

            maestro.newWorkflow(VerificationWorkflow.class,
                    WorkflowOptions.builder()
                            .workflowId(workflowId)
                            .build()
            ).startAsync(task);

            logger.info("Started verification workflow '{}' (amount={}, latency={})",
                    workflowId, request.amount(), latency);
        } catch (WorkflowAlreadyExistsException e) {
            // Duplicate delivery — the workflow is already running or done.
            logger.info("Verification workflow '{}' already exists — ignoring duplicate request",
                    workflowId);
        } catch (IllegalArgumentException e) {
            logger.error("Rejected verification request for loan {}: {}",
                    request.loanId(), e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to start verification workflow '{}': {}",
                    workflowId, e.getMessage(), e);
        }
    }
}
