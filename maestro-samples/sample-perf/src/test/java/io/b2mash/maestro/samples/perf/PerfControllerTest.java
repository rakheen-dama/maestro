package io.b2mash.maestro.samples.perf;

import com.zaxxer.hikari.HikariDataSource;
import io.b2mash.maestro.samples.perf.web.PerfController;
import io.b2mash.maestro.samples.perf.web.PerfController.ErrorResponse;
import io.b2mash.maestro.samples.perf.web.PerfController.SignalRequest;
import io.b2mash.maestro.samples.perf.web.PerfController.StartResponse;
import io.b2mash.maestro.samples.perf.web.StartRequest;
import io.b2mash.maestro.samples.perf.workflow.HoldInput;
import io.b2mash.maestro.samples.perf.workflow.PerfHoldWorkflow;
import io.b2mash.maestro.samples.perf.workflow.PerfSignalsWorkflow;
import io.b2mash.maestro.samples.perf.workflow.SignalsInput;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The controller's own wiring, without a running engine: the signal endpoint
 * rejects out-of-bounds input with {@code 400 {"error"}} before it touches the
 * client (whose collaborators are {@code null} here, so reaching it would throw),
 * the start endpoint answers {@code 202} for the {@code hold} and
 * {@code signals} types after handing the right workflow and input to its
 * starter (a recording one here) and {@code 400} with the rule's text for a
 * bad knob, and {@code /perf/info} reports the timer poll interval, the lock
 * TTL and the sweep intervals from the bound properties rather than constants.
 */
class PerfControllerTest {

    private final HikariDataSource dataSource = new HikariDataSource();
    private final List<Started> started = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void signalRejectsAnInvalidWorkflowId() {
        var response = controller(Map.of()).signal("run 1", "perf.release", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new ErrorResponse("workflowId must match [A-Za-z0-9._-]{1,200}"), response.getBody());
    }

    @Test
    void signalRejectsAnOverlongName() {
        var response = controller(Map.of()).signal("r1", "n".repeat(256), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new ErrorResponse("signal name must be 1..255 characters"), response.getBody());
    }

    @Test
    void signalRejectsAnOverlongPayload() {
        var response = controller(Map.of()).signal("r1", "perf.release", new SignalRequest("p".repeat(1025)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new ErrorResponse("payload must be at most 1024 characters"), response.getBody());
    }

    @Test
    void holdStartAnswers202AndStartsPerfHold() {
        var response = controller(Map.of())
                .start(new StartRequest("h1", "hold", 3, null, null, null, 500L, null));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(new StartResponse("h1"), response.getBody());
        assertEquals(List.of(new Started(PerfHoldWorkflow.class, "h1", new HoldInput(3, 500))), started);
    }

    @Test
    void signalsStartAnswers202AndStartsPerfSignals() {
        var response = controller(Map.of())
                .start(new StartRequest("s1", "signals", null, null, null, 60L, null, 10));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(new StartResponse("s1"), response.getBody());
        assertEquals(List.of(new Started(PerfSignalsWorkflow.class, "s1", new SignalsInput(10, 60))), started);
    }

    @Test
    void holdStartRejectsABadKnob() {
        var response = controller(Map.of())
                .start(new StartRequest("h1", "hold", null, null, null, null, 20_001L, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new ErrorResponse("holdMillis must be 0..20000"), response.getBody());
        assertEquals(List.of(), started);
    }

    @Test
    void signalsStartRejectsABadKnob() {
        var response = controller(Map.of())
                .start(new StartRequest("s1", "signals", null, null, null, null, null, 0));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new ErrorResponse("signals must be 1..50"), response.getBody());
        assertEquals(List.of(), started);
    }

    @Test
    void infoReportsTheBoundTimerPollInterval() {
        assertEquals("PT2S", controller(Map.of("maestro.timer.poll-interval", "2s")).info().timerPollInterval());
        assertEquals("PT5S", controller(Map.of()).info().timerPollInterval());
    }

    @Test
    void infoReportsTheBoundLockTtl() {
        assertEquals("PT30S", controller(Map.of()).info().lockTtl());
        assertEquals("PT45S", controller(Map.of("maestro.lock.ttl", "45s")).info().lockTtl());
    }

    @Test
    void infoReportsTheBoundSweepIntervals() {
        var defaults = controller(Map.of()).info();
        assertEquals("PT10M", defaults.lockCleanupInterval());
        assertEquals("PT1H", defaults.signalJanitorInterval());
        assertEquals(true, defaults.retentionEnabled());
        assertEquals("PT1H", defaults.retentionInterval());

        var bound = controller(Map.of(
                "maestro.lock.cleanup-interval", "90s",
                "maestro.signal.janitor-interval", "7m",
                "maestro.retention.enabled", "false",
                "maestro.retention.interval", "2h")).info();
        assertEquals("PT1M30S", bound.lockCleanupInterval());
        assertEquals("PT7M", bound.signalJanitorInterval());
        assertEquals(false, bound.retentionEnabled());
        assertEquals("PT2H", bound.retentionInterval());
    }

    private PerfController controller(Map<String, String> properties) {
        var bound = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("maestro", MaestroProperties.class);
        var client = new MaestroClient(null, null, null, null);
        var build = new StaticListableBeanFactory().getBeanProvider(BuildProperties.class);
        return new PerfController((workflowClass, workflowId, input) ->
                started.add(new Started(workflowClass, workflowId, input)), client, bound, dataSource, build);
    }

    /** One start the controller handed to its starter. */
    private record Started(Class<?> workflowClass, String workflowId, Object input) {}
}
