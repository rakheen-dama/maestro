package io.b2mash.maestro.admin.controller;

import io.b2mash.maestro.admin.repository.EventRepository;
import io.b2mash.maestro.admin.repository.MetricsRepository;
import io.b2mash.maestro.admin.repository.ServiceRepository;
import io.b2mash.maestro.admin.repository.WorkflowRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Main dashboard controller serving full Thymeleaf pages for the admin UI.
 *
 * <p>Handles the overview dashboard, workflow listing with filtering and pagination,
 * individual workflow detail view, failed workflows view, and signal/timer monitors.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All injected repositories are thread-safe and
 * Spring MVC guarantees that each request is handled in its own scope.
 */
@Controller
public class DashboardController {

    private final MetricsRepository metricsRepository;
    private final ServiceRepository serviceRepository;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;

    /**
     * Creates a new dashboard controller.
     *
     * @param metricsRepository  repository for workflow metrics
     * @param serviceRepository  repository for discovered services
     * @param workflowRepository repository for projected workflow state
     * @param eventRepository    repository for lifecycle events
     */
    public DashboardController(
            MetricsRepository metricsRepository,
            ServiceRepository serviceRepository,
            WorkflowRepository workflowRepository,
            EventRepository eventRepository
    ) {
        this.metricsRepository = metricsRepository;
        this.serviceRepository = serviceRepository;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Redirects the root path to the admin overview.
     *
     * @return redirect to {@code /admin}
     */
    @GetMapping("/")
    public String index() {
        return "redirect:/admin";
    }

    /**
     * Renders the admin overview page with service metrics.
     *
     * @param model the Thymeleaf model
     * @return the overview view name
     */
    @GetMapping("/admin")
    public String overview(Model model) {
        model.addAttribute("services", serviceRepository.findAll());
        model.addAttribute("overview", metricsRepository.getOverview());
        return "overview";
    }

    /**
     * Renders the paginated workflow list with optional filtering by service,
     * status, and free-text search.
     *
     * @param service filter by service name, or {@code null} for all services
     * @param status  filter by workflow status, or {@code null} for all statuses
     * @param search  free-text search against workflow ID and type, or {@code null}
     * @param offset  zero-based pagination offset
     * @param limit   page size
     * @param model   the Thymeleaf model
     * @return the workflows view name
     */
    @GetMapping("/admin/workflows")
    public String workflows(
            @RequestParam(required = false) @Nullable String service,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String search,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            Model model
    ) {
        var page = workflowRepository.findAll(service, status, search, offset, limit);
        model.addAttribute("page", page);
        model.addAttribute("service", service);
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("services", serviceRepository.findAll());
        return "workflows";
    }

    /**
     * Renders the detail view for a single workflow, including its event timeline.
     *
     * <p>If the workflow is not found, redirects back to the workflow list.
     *
     * @param workflowId the business workflow ID
     * @param model      the Thymeleaf model
     * @return the workflow-detail view name, or a redirect if not found
     */
    @GetMapping("/admin/workflows/{workflowId}")
    public String workflowDetail(@PathVariable String workflowId, Model model) {
        var workflow = workflowRepository.findByWorkflowId(workflowId);
        if (workflow.isEmpty()) {
            return "redirect:/admin/workflows";
        }
        var events = eventRepository.findByWorkflowInstanceId(workflow.get().workflowInstanceId());
        model.addAttribute("workflow", workflow.get());
        model.addAttribute("events", events);
        return "workflow-detail";
    }

    /**
     * Renders the paginated list of failed workflows.
     *
     * @param offset zero-based pagination offset
     * @param limit  page size
     * @param model  the Thymeleaf model
     * @return the failed view name
     */
    @GetMapping("/admin/failed")
    public String failed(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            Model model
    ) {
        model.addAttribute("page", workflowRepository.findFailed(offset, limit));
        return "failed";
    }

    /**
     * Renders the signal monitor page showing signal-related events.
     *
     * @param offset zero-based pagination offset
     * @param limit  page size
     * @param model  the Thymeleaf model
     * @return the signals view name
     */
    @GetMapping("/admin/signals")
    public String signals(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Model model
    ) {
        var page = eventRepository.findByEventTypes(
                List.of("SIGNAL_RECEIVED", "SIGNAL_TIMEOUT"), offset, limit);
        model.addAttribute("page", page);
        return "signals";
    }

    /**
     * Renders the timer monitor page showing timer-related events.
     *
     * @param offset zero-based pagination offset
     * @param limit  page size
     * @param model  the Thymeleaf model
     * @return the timers view name
     */
    @GetMapping("/admin/timers")
    public String timers(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Model model
    ) {
        var page = eventRepository.findByEventTypes(
                List.of("TIMER_SCHEDULED", "TIMER_FIRED"), offset, limit);
        model.addAttribute("page", page);
        return "timers";
    }
}
