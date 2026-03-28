package io.maestro.admin.controller;

import io.maestro.admin.service.AdminCommandService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Controller for admin actions that mutate workflow state.
 *
 * <p>Handles retry, terminate, and signal commands sent from the admin dashboard.
 * Each action publishes a command via Kafka through {@link AdminCommandService}
 * and redirects back to the workflow detail page with a flash message indicating
 * success or failure.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The injected {@link AdminCommandService} is
 * thread-safe and Spring MVC handles each request in its own scope.
 */
@Controller
public class AdminActionController {

    private final AdminCommandService commandService;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new admin action controller.
     *
     * @param commandService service for sending admin commands via Kafka
     * @param objectMapper   Jackson 3 ObjectMapper for parsing signal payloads
     */
    public AdminActionController(AdminCommandService commandService, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends a retry command for a failed workflow.
     *
     * <p>The workflow will resume execution from its last failed step.
     *
     * @param workflowId    the business workflow ID to retry
     * @param redirectAttrs flash attributes for the redirect response
     * @return redirect to the workflow detail page
     */
    @PostMapping("/admin/workflows/{workflowId}/retry")
    public String retry(@PathVariable String workflowId, RedirectAttributes redirectAttrs) {
        try {
            commandService.retry(workflowId);
            redirectAttrs.addFlashAttribute("message", "Retry command sent for workflow " + workflowId);
        } catch (Exception e) {
            redirectAttrs.addFlashAttribute("error", "Failed to send retry: " + e.getMessage());
        }
        return "redirect:/admin/workflows/" + workflowId;
    }

    /**
     * Sends a terminate command for a running workflow.
     *
     * <p>The workflow will be terminated immediately without compensation.
     *
     * @param workflowId    the business workflow ID to terminate
     * @param redirectAttrs flash attributes for the redirect response
     * @return redirect to the workflow detail page
     */
    @PostMapping("/admin/workflows/{workflowId}/terminate")
    public String terminate(@PathVariable String workflowId, RedirectAttributes redirectAttrs) {
        try {
            commandService.terminate(workflowId);
            redirectAttrs.addFlashAttribute("message",
                    "Terminate command sent for workflow " + workflowId);
        } catch (Exception e) {
            redirectAttrs.addFlashAttribute("error",
                    "Failed to send terminate: " + e.getMessage());
        }
        return "redirect:/admin/workflows/" + workflowId;
    }

    /**
     * Sends an application-level signal to a workflow.
     *
     * @param workflowId    the business workflow ID to signal
     * @param signalName    the signal name
     * @param payload       optional signal payload as a JSON string, parsed to a JsonNode
     * @param redirectAttrs flash attributes for the redirect response
     * @return redirect to the workflow detail page
     */
    @PostMapping("/admin/workflows/{workflowId}/signal")
    public String signal(
            @PathVariable String workflowId,
            @RequestParam String signalName,
            @RequestParam(required = false) @Nullable String payload,
            RedirectAttributes redirectAttrs
    ) {
        try {
            JsonNode payloadNode = null;
            if (payload != null && !payload.isBlank()) {
                payloadNode = objectMapper.readTree(payload);
            }
            commandService.signal(workflowId, signalName, payloadNode);
            redirectAttrs.addFlashAttribute("message",
                    "Signal '" + signalName + "' sent to workflow " + workflowId);
        } catch (Exception e) {
            redirectAttrs.addFlashAttribute("error",
                    "Failed to send signal: " + e.getMessage());
        }
        return "redirect:/admin/workflows/" + workflowId;
    }
}
