package io.maestro.admin.service;

import io.maestro.admin.config.AdminProperties;
import io.maestro.admin.repository.WorkflowRepository;
import io.maestro.core.spi.SignalMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends admin commands (retry, terminate, signal) back to workflow services via Kafka.
 *
 * <p>Each command is serialized as a {@link SignalMessage} and published to the
 * per-service signal topic derived from the workflow's service name and the
 * configured {@link AdminProperties#getSignalTopicPrefix() signal topic prefix}.
 *
 * <p>Internal command signals use the {@code $maestro:} prefix to distinguish
 * them from application-level signals:
 * <ul>
 *   <li>{@code $maestro:retry} — triggers workflow retry from the last failed step.</li>
 *   <li>{@code $maestro:terminate} — terminates the workflow immediately.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. {@link KafkaTemplate} and {@link ObjectMapper}
 * are both thread-safe.
 */
@Service
public class AdminCommandService {

    private static final Logger logger = LoggerFactory.getLogger(AdminCommandService.class);

    private static final String RETRY_SIGNAL = "$maestro:retry";
    private static final String TERMINATE_SIGNAL = "$maestro:terminate";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AdminProperties adminProperties;
    private final WorkflowRepository workflowRepository;

    /**
     * Creates a new admin command service.
     *
     * @param kafkaTemplate      Kafka producer template for sending signal messages
     * @param objectMapper       Jackson 3 ObjectMapper for serialization
     * @param adminProperties    admin configuration (signal topic prefix)
     * @param workflowRepository repository for looking up workflow service names
     */
    public AdminCommandService(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ObjectMapper objectMapper,
            AdminProperties adminProperties,
            WorkflowRepository workflowRepository
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.adminProperties = adminProperties;
        this.workflowRepository = workflowRepository;
    }

    /**
     * Sends a retry command to the service that owns the given workflow.
     *
     * <p>The workflow will resume execution from its last failed step.
     *
     * @param workflowId the business workflow ID to retry
     * @throws IllegalArgumentException if the workflow is not found in the admin database
     */
    public void retry(String workflowId) {
        logger.info("Sending retry command for workflow '{}'", workflowId);
        sendSignal(workflowId, RETRY_SIGNAL, null);
    }

    /**
     * Sends a terminate command to the service that owns the given workflow.
     *
     * <p>The workflow will be terminated immediately without compensation.
     *
     * @param workflowId the business workflow ID to terminate
     * @throws IllegalArgumentException if the workflow is not found in the admin database
     */
    public void terminate(String workflowId) {
        logger.info("Sending terminate command for workflow '{}'", workflowId);
        sendSignal(workflowId, TERMINATE_SIGNAL, null);
    }

    /**
     * Sends an application-level signal to the service that owns the given workflow.
     *
     * @param workflowId the business workflow ID to signal
     * @param signalName the signal name
     * @param payload    optional signal payload, may be {@code null}
     * @throws IllegalArgumentException if the workflow is not found in the admin database
     */
    public void signal(String workflowId, String signalName, @Nullable JsonNode payload) {
        logger.info("Sending signal '{}' to workflow '{}'", signalName, workflowId);
        sendSignal(workflowId, signalName, payload);
    }

    // ── Internal ─────────────────────────────────────────────────────

    private void sendSignal(String workflowId, String signalName, @Nullable JsonNode payload) {
        var workflow = workflowRepository.findByWorkflowId(workflowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow not found in admin database: " + workflowId));

        var topic = adminProperties.getSignalTopicPrefix() + workflow.serviceName();
        var message = new SignalMessage(workflowId, signalName, payload);

        try {
            var bytes = objectMapper.writeValueAsBytes(message);
            kafkaTemplate.send(topic, workflowId, bytes);
            logger.info("Sent signal '{}' to workflow '{}' on topic '{}'",
                    signalName, workflowId, topic);
        } catch (Exception e) {
            logger.error("Failed to send signal '{}' to workflow '{}' on topic '{}': {}",
                    signalName, workflowId, topic, e.getMessage(), e);
            throw new IllegalStateException(
                    "Failed to send signal '" + signalName + "' to workflow '" + workflowId + "'", e);
        }
    }
}
