package io.b2mash.maestro.samples.perf.web;

import com.zaxxer.hikari.HikariDataSource;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.samples.perf.workflow.PerfChainWorkflow;
import io.b2mash.maestro.samples.perf.workflow.PerfParkWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Objects;

/**
 * The HTTP surface the k6 harness drives: start a run, signal a run, and
 * identify this node. The JSON shapes are the harness's wire contract.
 *
 * <ul>
 *   <li>{@code POST /perf/workflows} — {@link StartRequest} → {@code 202 {"workflowId"}},
 *       {@code 400 {"error"}} on validation, {@code 409 {"error"}} if the ID exists</li>
 *   <li>{@code POST /perf/workflows/{id}/signals/{name}} — {@code {"payload"?}} →
 *       {@code 202 {"workflowId", "signal"}}</li>
 *   <li>{@code GET /perf/info} — {@link PerfInfo}</li>
 * </ul>
 *
 * <p>Thread-safe: holds only immutable state and thread-safe beans.
 */
@RestController
@RequestMapping("/perf")
public class PerfController {

    private final MaestroClient maestro;
    private final MaestroProperties properties;
    private final DataSource dataSource;
    private final @Nullable BuildProperties build;
    private final String hostname = resolveHostname();

    /**
     * Creates the controller.
     *
     * @param maestro    the Maestro client
     * @param properties the bound {@code maestro.*} properties — the same bean the engine reads
     * @param dataSource the application's {@code DataSource}
     * @param build      the jar's build info; absent when run without {@code bootBuildInfo}
     */
    public PerfController(MaestroClient maestro, MaestroProperties properties, DataSource dataSource,
                          ObjectProvider<BuildProperties> build) {
        this.maestro = maestro;
        this.properties = properties;
        this.dataSource = dataSource;
        this.build = build.getIfAvailable();
    }

    /**
     * Starts a {@code perf-chain} or {@code perf-park} run.
     *
     * @param request the run's ID, type and knobs
     * @return {@code 202} with the workflow ID, {@code 400} or {@code 409} with an error
     */
    @PostMapping("/workflows")
    public ResponseEntity<?> start(@RequestBody StartRequest request) {
        var error = StartRequestValidator.validate(request);
        if (error.isPresent()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(error.get()));
        }
        var workflowId = Objects.requireNonNull(request.workflowId());
        var options = WorkflowOptions.builder().workflowId(workflowId).build();
        try {
            if (StartRequest.CHAIN.equals(request.type())) {
                maestro.newWorkflow(PerfChainWorkflow.class, options).startAsync(request.chainInput());
            } else {
                maestro.newWorkflow(PerfParkWorkflow.class, options).startAsync(request.parkInput());
            }
        } catch (WorkflowAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("workflow '" + workflowId + "' already exists"));
        }
        return ResponseEntity.accepted().body(new StartResponse(workflowId));
    }

    /**
     * Delivers a signal to a run (persisted at once; stored for later if the run
     * has not reached its await, or does not exist yet).
     *
     * @param workflowId the run's workflow ID
     * @param name       the signal name ({@code perf.release} releases a park run)
     * @param body       optional {@code {"payload": str}}
     * @return {@code 202} with the workflow ID and signal name
     */
    @PostMapping("/workflows/{workflowId}/signals/{name}")
    public ResponseEntity<SignalResponse> signal(@PathVariable String workflowId, @PathVariable String name,
                                                 @RequestBody(required = false) @Nullable SignalRequest body) {
        maestro.getWorkflow(workflowId).signal(name, body == null ? null : body.payload());
        return ResponseEntity.accepted().body(new SignalResponse(workflowId, name));
    }

    /**
     * Identifies this node and reports the settings it actually runs with.
     *
     * @return this node's {@link PerfInfo}
     */
    @GetMapping("/info")
    public PerfInfo info() {
        var runtime = Runtime.getRuntime();
        var worker = properties.getWorker();
        return new PerfInfo(
                buildValue("gitCommit"),
                build == null || build.getTime() == null ? "unknown" : build.getTime().toString(),
                build == null || build.getVersion() == null ? "unknown" : build.getVersion(),
                hostname,
                ProcessHandle.current().pid(),
                ManagementFactory.getRuntimeMXBean().getStartTime(),
                Runtime.version().toString(),
                runtime.availableProcessors(),
                runtime.maxMemory(),
                Objects.requireNonNullElse(properties.getServiceName(), ""),
                properties.getAdmin().events().enabled(),
                worker.defaultConcurrency(),
                worker.defaultActivityConcurrency(),
                worker.taskQueues().stream().map(MaestroProperties.TaskQueueProperties::name).toList(),
                properties.getSignal().wakeRecheckInterval().toString(),
                properties.getRecovery().pollInterval().toString(),
                hikariMaximumPoolSize());
    }

    /**
     * Answers an unparseable body (malformed JSON, a knob of the wrong type)
     * with the same {@code {"error"}} shape as a validation failure.
     *
     * @param e the parse failure
     * @return {@code 400} with an error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("unreadable request body"));
    }

    private String buildValue(String key) {
        var value = build == null ? null : build.get(key);
        return value == null ? "unknown" : value;
    }

    private int hikariMaximumPoolSize() {
        try {
            return dataSource.isWrapperFor(HikariDataSource.class)
                    ? dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()
                    : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return Objects.requireNonNullElse(System.getenv("HOSTNAME"), "unknown");
        }
    }

    /**
     * Body of a {@code 202} start.
     *
     * @param workflowId the started run's workflow ID
     */
    public record StartResponse(String workflowId) {}

    /**
     * Body of a {@code 400} or {@code 409}.
     *
     * @param error what was wrong
     */
    public record ErrorResponse(String error) {}

    /**
     * Body of a signal request.
     *
     * @param payload the signal payload, or {@code null}
     */
    public record SignalRequest(@Nullable String payload) {}

    /**
     * Body of a {@code 202} signal.
     *
     * @param workflowId the signalled run's workflow ID
     * @param signal     the signal name
     */
    public record SignalResponse(String workflowId, String signal) {}
}
