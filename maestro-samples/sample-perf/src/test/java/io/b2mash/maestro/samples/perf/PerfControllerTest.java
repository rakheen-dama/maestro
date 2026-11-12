package io.b2mash.maestro.samples.perf;

import com.zaxxer.hikari.HikariDataSource;
import io.b2mash.maestro.samples.perf.web.PerfController;
import io.b2mash.maestro.samples.perf.web.PerfController.ErrorResponse;
import io.b2mash.maestro.samples.perf.web.PerfController.SignalRequest;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The controller's own wiring, without a running engine: the signal endpoint
 * rejects out-of-bounds input with {@code 400 {"error"}} before it touches the
 * client (whose collaborators are {@code null} here, so reaching it would throw),
 * and {@code /perf/info} reports the timer poll interval from the bound
 * properties rather than a constant.
 */
class PerfControllerTest {

    private final HikariDataSource dataSource = new HikariDataSource();

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
    void infoReportsTheBoundTimerPollInterval() {
        assertEquals("PT2S", controller(Map.of("maestro.timer.poll-interval", "2s")).info().timerPollInterval());
        assertEquals("PT5S", controller(Map.of()).info().timerPollInterval());
    }

    private PerfController controller(Map<String, String> properties) {
        var bound = new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("maestro", MaestroProperties.class);
        var client = new MaestroClient(null, null, null, null);
        var build = new StaticListableBeanFactory().getBeanProvider(BuildProperties.class);
        return new PerfController(client, bound, dataSource, build);
    }
}
