package io.maestro.admin.controller;

import io.maestro.admin.repository.EventRepository;
import io.maestro.admin.repository.MetricsRepository;
import io.maestro.admin.repository.ServiceRepository;
import io.maestro.admin.repository.WorkflowRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Controller for HTMX fragment responses — partial HTML for polling updates.
 *
 * <p>These endpoints return Thymeleaf fragment templates instead of full pages,
 * enabling HTMX to swap specific parts of the UI without full page reloads.
 * The overview metrics fragment is polled every 5 seconds; other fragments
 * are loaded on demand for pagination and filtering.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All injected repositories are thread-safe and
 * Spring MVC guarantees that each request is handled in its own scope.
 */
@Controller
public class FragmentController {

    private final MetricsRepository metricsRepository;
    private final ServiceRepository serviceRepository;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;

    /**
     * Creates a new fragment controller.
     *
     * @param metricsRepository  repository for workflow metrics
     * @param serviceRepository  repository for discovered services
     * @param workflowRepository repository for projected workflow state
     * @param eventRepository    repository for lifecycle events
     */
    public FragmentController(
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
     * Returns the overview metrics fragment, polled by the overview page every 5 seconds.
     *
     * @param model the Thymeleaf model
     * @return the overview-metrics fragment view name
     */
    @GetMapping("/admin/fragments/overview")
    public String overviewFragment(Model model) {
        model.addAttribute("services", serviceRepository.findAll());
        model.addAttribute("overview", metricsRepository.getOverview());
        return "fragments/overview-metrics";
    }

    /**
     * Returns the workflow table fragment for HTMX pagination and filtering.
     *
     * @param service filter by service name, or {@code null} for all services
     * @param status  filter by workflow status, or {@code null} for all statuses
     * @param search  free-text search against workflow ID and type, or {@code null}
     * @param offset  zero-based pagination offset
     * @param limit   page size
     * @param model   the Thymeleaf model
     * @return the workflow-table fragment view name
     */
    @GetMapping("/admin/fragments/workflows")
    public String workflowsFragment(
            @RequestParam(required = false) @Nullable String service,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String search,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            Model model
    ) {
        model.addAttribute("page", workflowRepository.findAll(service, status, search, offset, limit));
        model.addAttribute("service", service);
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        return "fragments/workflow-table";
    }

    /**
     * Returns the failed workflows table fragment for HTMX pagination.
     *
     * @param offset zero-based pagination offset
     * @param limit  page size
     * @param model  the Thymeleaf model
     * @return the failed-table fragment view name
     */
    @GetMapping("/admin/fragments/failed")
    public String failedFragment(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            Model model
    ) {
        model.addAttribute("page", workflowRepository.findFailed(offset, limit));
        return "fragments/failed-table";
    }

    /**
     * Returns the signal monitor table fragment for HTMX pagination.
     *
     * @param offset zero-based pagination offset
     * @param limit  page size
     * @param model  the Thymeleaf model
     * @return the signal-table fragment view name
     */
    @GetMapping("/admin/fragments/signals")
    public String signalsFragment(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Model model
    ) {
        model.addAttribute("page", eventRepository.findByEventTypes(
                List.of("SIGNAL_RECEIVED", "SIGNAL_TIMEOUT"), offset, limit));
        return "fragments/signal-table";
    }

    /**
     * Returns the timer monitor table fragment for HTMX pagination.
     *
     * @param offset zero-based pagination offset
     * @param limit  page size
     * @param model  the Thymeleaf model
     * @return the timer-table fragment view name
     */
    @GetMapping("/admin/fragments/timers")
    public String timersFragment(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Model model
    ) {
        model.addAttribute("page", eventRepository.findByEventTypes(
                List.of("TIMER_SCHEDULED", "TIMER_FIRED"), offset, limit));
        return "fragments/timer-table";
    }
}
