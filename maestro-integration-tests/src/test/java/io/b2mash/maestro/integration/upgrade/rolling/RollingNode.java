package io.b2mash.maestro.integration.upgrade.rolling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * One rolling-upgrade node: a JVM of its own, launched from one build's node
 * classpath ({@link RollingNodeManifest}), driven over its control port
 * ({@code io.b2mash.maestro.rollingnode.NodeControlServer}).
 *
 * <p>Everything the node prints goes to {@code <logDir>/<name>.log}; the
 * {@code ROLLING-EVENT|...} lines its observer prints are also kept in memory
 * for assertions ({@link #events()}).
 *
 * <p>Stopping is by this node's own {@link Process} handle only, never by
 * name or pattern (also from the JVM shutdown hook that kills every node a
 * stopped test worker leaves behind): {@link #stopGracefully()} sends SIGTERM (Spring's shutdown
 * hook runs the engine's graceful shutdown, as a deploy does) and
 * {@link #kill()} SIGKILL (a crash: nothing runs, the instance lock expires).
 *
 * <p><b>Thread safety:</b> one test thread drives a node; the reader thread
 * only appends to thread-safe collections.
 */
final class RollingNode implements AutoCloseable {

    private static final String READY = "ROLLING-NODE READY ";
    private static final String SYNC = "ROLLING-SYNC ";
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(15);
    private static final String MAIN_CLASS = "io.b2mash.maestro.rollingnode.RollingNodeApplication";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /** A callback the node's {@code RollingObserver} reported. */
    record Event(String callback, String workflowId, String reason, String detail) {
        static Event parse(String line) {
            var parts = line.split("\\|", -1);
            return new Event(parts[1], parts[2], parts[3], parts.length > 4 ? parts[4] : "");
        }
    }

    /**
     * Every node process this JVM launched. A test worker stopped from outside
     * (Ctrl-C, a cancelled Gradle run) never reaches {@code @AfterEach}; this
     * JVM's shutdown hook kills them instead, through their own handles, so no
     * node outlives the test.
     */
    private static final List<Process> LAUNCHED = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> LAUNCHED.forEach(Process::destroyForcibly), "rolling-node-reaper"));
    }

    private final String name;
    private final Process process;
    private final Path log;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Integer> port = new CompletableFuture<>();
    private final Set<String> syncTokens = ConcurrentHashMap.newKeySet();
    private Thread reader;
    private volatile String engine;
    private volatile String starter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private RollingNode(String name, Process process, Path log) {
        this.name = name;
        this.process = process;
        this.log = log;
    }

    /**
     * Launches a node and waits until it is ready: the engine's pollers have
     * started and the control port is open.
     *
     * @param name       the node's name, recorded with every side effect it runs
     * @param classpath  the node build's runtime classpath
     * @param properties Spring Boot properties for the node
     * @param logDir     where the node's output goes
     * @return the ready node
     */
    static RollingNode launch(String name, String classpath, Map<String, String> properties, Path logDir) {
        var command = new ArrayList<String>();
        command.add(javaExecutable());
        command.add("-Xmx384m");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-cp");
        command.add(classpath);
        command.add(MAIN_CLASS);
        var all = new LinkedHashMap<>(properties);
        all.put("rolling.node-name", name);
        all.forEach((key, value) -> command.add("--" + key + "=" + value));
        Path log = logDir.resolve(name + ".log");
        Process process;
        try {
            Files.createDirectories(logDir);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            LAUNCHED.add(process);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot launch node " + name, e);
        }
        var node = new RollingNode(name, process, log);
        node.startReader();
        node.awaitReady();
        return node;
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private void startReader() {
        reader = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8), true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.println(line);
                    if (line.startsWith("ROLLING-EVENT|")) {
                        events.add(Event.parse(line));
                    } else if (line.startsWith(SYNC)) {
                        syncTokens.add(line.substring(SYNC.length()).strip());
                    } else if (line.startsWith(READY)) {
                        var fields = new java.util.HashMap<String, String>();
                        for (var field : line.substring("ROLLING-NODE READY ".length()).split(" ")) {
                            var eq = field.indexOf('=');
                            fields.put(field.substring(0, eq), field.substring(eq + 1));
                        }
                        engine = fields.get("engine");
                        starter = fields.get("starter");
                        port.complete(Integer.parseInt(fields.get("port")));
                    }
                }
            } catch (IOException e) {
                // the process ended; whatever it printed is in the log
            } finally {
                port.completeExceptionally(new IllegalStateException("node " + name + " exited before it was ready"));
            }
        }, "rolling-node-reader-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    private void awaitReady() {
        try {
            port.get(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            process.destroyForcibly();
            throw new IllegalStateException("Node " + name + " did not become ready; see " + log + "\n" + tail(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted waiting for node " + name, e);
        }
    }

    /** @return where the node loaded {@code maestro-core} from (a URL) */
    String engineLocation() {
        return engine;
    }

    /** @return where the node loaded {@code maestro-spring-boot-starter} from (a URL) */
    String starterLocation() {
        return starter;
    }

    /** @return the node's name */
    String name() {
        return name;
    }

    /** @return the callbacks the node's observer reported so far */
    List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * The callbacks read so far. The node prints them on its own threads and
     * this JVM reads them on another, so a callback can arrive after a state
     * the test waited for in the database. Use this only while polling; assert
     * a callback's presence with {@link #awaitEvent} and its absence on
     * {@link #settledEvents}.
     *
     * @param workflowId a workflow
     * @return the callbacks the node reported for it, as read so far
     */
    List<Event> events(String workflowId) {
        return events.stream().filter(e -> e.workflowId().equals(workflowId)).toList();
    }

    /**
     * Waits until the node has reported {@code callback} for the workflow.
     *
     * @return the first such callback
     * @throws AssertionError if it does not arrive within the settle timeout
     */
    Event awaitEvent(String workflowId, String callback) {
        var deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (true) {
            var found = events(workflowId).stream().filter(e -> e.callback().equals(callback)).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Node " + name + " did not report " + callback + " for " + workflowId
                        + " within " + SETTLE_TIMEOUT + "; it reported " + events(workflowId) + "; see " + log);
            }
            pause();
        }
    }

    /**
     * Every callback the node printed before this call, for asserting that one
     * did <em>not</em> happen. For a node that has exited, the reader is joined
     * at end of stream, so the list is complete. For a live node, a
     * {@code /sync} token is printed through the same stream the observer
     * prints on and awaited, so every callback printed before it has been
     * read.
     *
     * @param workflowId a workflow
     * @return the callbacks the node reported for it
     */
    List<Event> settledEvents(String workflowId) {
        if (process.isAlive()) {
            var token = UUID.randomUUID().toString();
            post("/sync", Map.of("token", token));
            var deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (!syncTokens.contains(token)) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Node " + name + " did not echo sync token within " + SETTLE_TIMEOUT);
                }
                pause();
            }
        } else {
            try {
                reader.join(SETTLE_TIMEOUT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted draining node " + name, e);
            }
            if (reader.isAlive()) {
                throw new AssertionError("Node " + name + "'s output was not drained within " + SETTLE_TIMEOUT);
            }
        }
        return events(workflowId);
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** Starts a workflow on this node. */
    void start(String workflowType, String workflowId, String input) {
        post("/start", Map.of("type", workflowType, "id", workflowId, "input", input));
    }

    /** Signals a workflow through this node, with no message id. */
    void signal(String workflowId, String signalName, String payload) {
        post("/signal", Map.of("id", workflowId, "name", signalName, "payload", payload));
    }

    /**
     * Signals a workflow through this node with a client-supplied message id
     * ({@code WorkflowHandle.signal(name, payload, messageId)}).
     *
     * @return {@code true} if the node stored the signal, {@code false} if it
     *         suppressed it as a duplicate
     */
    boolean signal(String workflowId, String signalName, String payload, String messageId) {
        var body = post("/signal", Map.of("id", workflowId, "name", signalName, "payload", payload,
                "messageId", messageId));
        return switch (body) {
            case "persisted=true" -> true;
            case "persisted=false" -> false;
            default -> throw new IllegalStateException("Node " + name + " answered " + body);
        };
    }

    /**
     * Stops the node as a deploy does (SIGTERM) and waits for it to exit.
     *
     * @return the exit code
     */
    int stopGracefully() {
        process.destroy();
        return awaitExit();
    }

    /** Kills the node (SIGKILL) and waits for it to exit. */
    void kill() {
        process.destroyForcibly();
        awaitExit();
    }

    private int awaitExit() {
        try {
            if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                throw new IllegalStateException("Node " + name + " did not exit within " + STOP_TIMEOUT
                        + " of SIGTERM and was killed; see " + log);
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted waiting for node " + name + " to exit", e);
        }
    }

    /** @return the last lines of the node's log, for a failure message */
    String tail() {
        try {
            var lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log: " + e + ")";
        }
    }

    /** @return the node's log file */
    Path log() {
        return log;
    }

    /** Kills the node if it is still running. */
    @Override
    public void close() {
        if (process.isAlive()) {
            kill();
        }
    }

    private String post(String path, Map<String, String> params) {
        var query = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.join() + path + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Node " + name + " did not answer " + path + "; see " + log, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling node " + name, e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Node " + name + " refused " + path + "?" + query + ": "
                    + response.body());
        }
        return response.body();
    }
}
