package io.b2mash.maestro.rollingnode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.rollingnode.workflow.RollingSagaWorkflow;
import io.b2mash.maestro.rollingnode.workflow.RollingSignalWorkflow;
import io.b2mash.maestro.rollingnode.workflow.RollingTimerWorkflow;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The test's handle on this node: a JDK {@link HttpServer} on the loopback
 * interface, started once the application is ready (after the engine's
 * recovery and timer pollers have started). Every request is a {@code POST}
 * with URL-encoded query parameters and answers {@code 200 ok} (or the body
 * named below) or {@code 500 <exception>}.
 *
 * <ul>
 *   <li>{@code /start?type=<workflow type>&id=<workflowId>&input=<string>} —
 *       {@code MaestroClient.newWorkflow(...).startAsync(input)} on this node.</li>
 *   <li>{@code /signal?id=<workflowId>&name=<signal>&payload=<string>} —
 *       {@code MaestroClient.getWorkflow(id).signal(name, payload)} through this
 *       node. With {@code &messageId=<id>} it calls
 *       {@code signal(name, payload, messageId)} instead and answers
 *       {@code persisted=true} or {@code persisted=false} (a suppressed
 *       duplicate).</li>
 * </ul>
 *
 * <p>The JDK server keeps the node free of a web stack, so its classpath is the
 * engine's and nothing the test could confuse with it.
 *
 * <p><b>Thread safety:</b> requests are served one at a time on the server's
 * default executor; {@link MaestroClient} is thread-safe.
 */
@Component
public class NodeControlServer {

    private final MaestroClient client;
    private final int port;
    private final String nodeName;
    private HttpServer server;

    /**
     * @param client   the starter's client
     * @param port     {@code rolling.control-port}; {@code 0} picks a free port
     * @param nodeName {@code rolling.node-name}, echoed in the ready line
     */
    public NodeControlServer(
            MaestroClient client,
            @Value("${rolling.control-port:0}") int port,
            @Value("${rolling.node-name}") String nodeName
    ) {
        this.client = client;
        this.port = port;
        this.nodeName = nodeName;
    }

    /**
     * Opens the control port and prints the ready line the test waits for.
     *
     * @throws IOException if the port cannot be bound
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/start", exchange -> handle(exchange, this::startWorkflowOk));
        server.createContext("/signal", exchange -> handle(exchange, this::signal));
        server.start();
        // Where the engine and the starter were loaded from: the test checks
        // that each node really runs the build it was launched as.
        System.out.println("ROLLING-NODE READY port=" + server.getAddress().getPort() + " node=" + nodeName
                + " engine=" + codeSource(DurableWorkflow.class) + " starter=" + codeSource(MaestroClient.class));
        System.out.flush();
    }

    /** Closes the control port before the engine shuts down. */
    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }

    private void startWorkflow(Map<String, String> params) {
        var options = new WorkflowOptions(require(params, "id"));
        var input = require(params, "input");
        switch (require(params, "type")) {
            case RollingSignalWorkflow.TYPE -> client.newWorkflow(RollingSignalWorkflow.class, options).startAsync(input);
            case RollingTimerWorkflow.TYPE -> client.newWorkflow(RollingTimerWorkflow.class, options).startAsync(input);
            case RollingSagaWorkflow.TYPE -> client.newWorkflow(RollingSagaWorkflow.class, options).startAsync(input);
            default -> throw new IllegalArgumentException("unknown workflow type " + params.get("type"));
        }
    }

    private String startWorkflowOk(Map<String, String> params) {
        startWorkflow(params);
        return "ok";
    }

    private String signal(Map<String, String> params) {
        var handle = client.getWorkflow(require(params, "id"));
        var messageId = params.get("messageId");
        if (messageId == null) {
            handle.signal(require(params, "name"), require(params, "payload"));
            return "ok";
        }
        return "persisted=" + handle.signal(require(params, "name"), require(params, "payload"), messageId);
    }

    private interface Action {
        String run(Map<String, String> params);
    }

    private static void handle(HttpExchange exchange, Action action) throws IOException {
        int status;
        String body;
        try {
            body = action.run(query(exchange));
            status = 200;
        } catch (RuntimeException e) {
            status = 500;
            body = e.toString();
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        var params = new HashMap<String, String>();
        var raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return params;
        }
        for (var pair : raw.split("&")) {
            var eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static String require(Map<String, String> params, String name) {
        var value = params.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing parameter " + name);
        }
        return value;
    }
}
