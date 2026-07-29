package io.b2mash.maestro.admin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.ExecutionException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the real Maestro Admin Spring context (Postgres + Kafka via
 * Testcontainers) and exercises every full-page and HTMX-fragment endpoint
 * with {@link MockMvc}, proving the wiring end to end: Flyway migrations
 * apply, {@code JdbcTemplate}-backed repositories resolve, Thymeleaf renders
 * without a missing-variable exception, and the Kafka consumer lifecycle
 * bean starts without blocking context refresh.
 *
 * <p>This is deliberately a smoke suite (every documented endpoint returns
 * the expected status against an empty database), not a UI pixel test — see
 * {@link EventIngestionRoundTripTest} for the data-bearing path through
 * {@code DashboardController#workflowDetail}.
 */
@SpringBootTest(properties = {
        "maestro.admin.events-topic=" + DashboardSmokeMockMvcTest.ADMIN_TOPIC,
        "maestro.admin.consumer-group=" + DashboardSmokeMockMvcTest.GROUP
})
@AutoConfigureMockMvc
@DisplayName("Maestro Admin dashboard — endpoint smoke coverage")
class DashboardSmokeMockMvcTest extends AdminAppTestSupport {

    static final String ADMIN_TOPIC = "admin-smoke.events";
    static final String GROUP = "admin-smoke-group";

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void createTopic() throws ExecutionException, InterruptedException {
        createTopics(ADMIN_TOPIC);
    }

    @Test
    @DisplayName("GET / redirects to /admin")
    void root_redirectsToOverview() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    @DisplayName("GET /admin renders the overview page")
    void overview_returnsOk() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("services", "overview"));
    }

    @Test
    @DisplayName("GET /admin/workflows renders the paginated workflow list")
    void workflows_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/workflows"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("page", "services"));
    }

    @Test
    @DisplayName("GET /admin/workflows supports the service/status/search filters")
    void workflows_withFilters_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/workflows")
                        .param("service", "some-service")
                        .param("status", "RUNNING")
                        .param("search", "abc")
                        .param("offset", "0")
                        .param("limit", "10"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/workflows/{unknownId} redirects back to the list")
    void workflowDetail_unknownWorkflow_redirects() throws Exception {
        mockMvc.perform(get("/admin/workflows/does-not-exist"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/workflows"));
    }

    @Test
    @DisplayName("GET /admin/failed renders the failed-workflows page")
    void failed_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/failed"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("page"));
    }

    @Test
    @DisplayName("GET /admin/signals renders the signal monitor")
    void signals_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/signals"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("page"));
    }

    @Test
    @DisplayName("GET /admin/timers renders the timer monitor")
    void timers_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/timers"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("page"));
    }

    @Test
    @DisplayName("GET /admin/fragments/overview returns the HTMX polling fragment")
    void overviewFragment_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/fragments/overview"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/fragments/workflows returns the HTMX table fragment")
    void workflowsFragment_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/fragments/workflows"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/fragments/failed returns the HTMX table fragment")
    void failedFragment_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/fragments/failed"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/fragments/signals returns the HTMX table fragment")
    void signalsFragment_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/fragments/signals"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/fragments/timers returns the HTMX table fragment")
    void timersFragment_returnsOk() throws Exception {
        mockMvc.perform(get("/admin/fragments/timers"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST retry on an unknown workflow fails gracefully with a flash error, not a 500")
    void retry_unknownWorkflow_redirectsWithFlashError() throws Exception {
        mockMvc.perform(post("/admin/workflows/does-not-exist/retry"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/workflows/does-not-exist"));
    }

    @Test
    @DisplayName("POST terminate on an unknown workflow fails gracefully with a flash error, not a 500")
    void terminate_unknownWorkflow_redirectsWithFlashError() throws Exception {
        mockMvc.perform(post("/admin/workflows/does-not-exist/terminate"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/workflows/does-not-exist"));
    }

    @Test
    @DisplayName("POST signal on an unknown workflow fails gracefully with a flash error, not a 500")
    void signal_unknownWorkflow_redirectsWithFlashError() throws Exception {
        mockMvc.perform(post("/admin/workflows/does-not-exist/signal")
                        .param("signalName", "some-signal"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/workflows/does-not-exist"));
    }
}
