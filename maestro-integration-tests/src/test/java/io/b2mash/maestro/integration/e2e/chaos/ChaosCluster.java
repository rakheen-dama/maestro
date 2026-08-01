package io.b2mash.maestro.integration.e2e.chaos;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Testcontainers-orchestrated chaos cluster (chaos-harness-design.md §2):
 * one Docker network carrying shared Postgres (with {@code pg_stat_statements}),
 * Kafka (KRaft, auto-create off), Valkey, and the six loan-origination workload
 * nodes running the sample boot jars as {@code eclipse-temurin:25-jre}
 * containers.
 *
 * <p>Nodes reach infrastructure by network alias ({@code postgres:5432},
 * {@code kafka:9092}, {@code valkey:6379}); the test JVM reaches nodes and
 * infra through mapped host ports, re-resolved after every replacement. A node
 * killed by the controller is replaced by a fresh container reusing the same
 * alias, and its stdout streams to a per-generation host log file from the
 * moment it starts, so log capture survives {@code kill -9}.
 *
 * <h2>Thread Safety</h2>
 * <p>Docker mutations (kill/pause/partition/replace/heal) are serialised by the
 * single-threaded {@link ChaosController}; read accessors (endpoints, data
 * sources) are safe for concurrent use by the driver, sampler and checker.
 */
public final class ChaosCluster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChaosCluster.class);

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String KAFKA_IMAGE = "apache/kafka:3.9.0";
    private static final String VALKEY_IMAGE = "valkey/valkey:8-alpine";
    private static final String NODE_IMAGE = "eclipse-temurin:25-jre";
    private static final int NODE_PORT = 8080;

    /** All topics the sample uses; Maestro never auto-creates topics. */
    private static final List<String> TOPICS = List.of(
            "maestro.tasks.loan-application", "maestro.tasks.verification-gateway",
            "maestro.tasks.underwriting", "maestro.signals.loan-application",
            "maestro.signals.verification-gateway", "maestro.signals.underwriting",
            "maestro.admin.events", "loans.verification.requests", "loans.verification.results",
            "loans.underwriting.requests", "loans.underwriting.decisions");

    private final ChaosConfig config;
    private final EvidenceWriter evidence;
    private final Path logDir;
    private final DockerClient docker = DockerClientFactory.instance().client();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private Network network;
    private PostgreSQLContainer<?> postgres;
    private GenericContainer<?> kafka;
    private GenericContainer<?> valkey;

    private final Map<NodeRole, ManagedNode> nodes = new ConcurrentHashMap<>();
    private final Set<NodeRole> paused = ConcurrentHashMap.newKeySet();
    private final Set<NodeRole> partitioned = ConcurrentHashMap.newKeySet();
    private final Set<NodeRole> dead = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedBackends = ConcurrentHashMap.newKeySet();

    /**
     * @param config   run configuration (jar paths, timeouts)
     * @param evidence evidence writer (owns the run dir; logs stream under it)
     */
    public ChaosCluster(ChaosConfig config, EvidenceWriter evidence) {
        this.config = config;
        this.evidence = evidence;
        this.logDir = evidence.runDir().resolve("logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wraps the currently-live container for a role plus its generation counter. */
    private static final class ManagedNode {
        final NodeRole role;
        volatile GenericContainer<?> container;
        volatile int generation;
        final List<Path> logFiles = new CopyOnWriteArrayList<>();

        ManagedNode(NodeRole role) {
            this.role = role;
        }
    }

    // ---------------------------------------------------------------- startup

    /** Boots infra, pre-creates topics, starts the six nodes and waits healthy. */
    public void start() {
        log.info("[chaos] starting cluster network + infrastructure");
        network = Network.newNetwork();

        postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withUsername("maestro").withPassword("maestro").withDatabaseName("maestro")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("chaos/init-loan-dbs.sh", 0755),
                        "/docker-entrypoint-initdb.d/init-loan-dbs.sh")
                .withCommand("postgres",
                        "-c", "shared_preload_libraries=pg_stat_statements",
                        "-c", "pg_stat_statements.track=all",
                        "-c", "pg_stat_statements.track_utility=on",
                        "-c", "pg_stat_statements.max=10000");
        postgres.start();

        // Single-node KRaft broker mirroring the sample's docker-compose exactly
        // (the empty-host listener form the apache/kafka image requires). Only
        // in-network peers (the six nodes, alias kafka:9092) talk to it; the test
        // JVM drives topic creation and consumer-group checks via execInContainer,
        // so no host-side Kafka listener is needed.
        kafka = new GenericContainer<>(DockerImageName.parse(KAFKA_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withEnv(kafkaEnv())
                .waitingFor(Wait.forLogMessage(".*Kafka Server started.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        kafka.start();

        valkey = new GenericContainer<>(DockerImageName.parse(VALKEY_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("valkey")
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
        valkey.start();

        createTopics();

        log.info("[chaos] starting 6 workload nodes");
        for (NodeRole role : NodeRole.values()) {
            ManagedNode node = new ManagedNode(role);
            nodes.put(role, node);
            node.container = newNodeContainer(node);
            node.container.start();
        }
        awaitAllNodesHealthy(Duration.ofMinutes(3));
        verifyValkeyLockMode();
        log.info("[chaos] cluster healthy: {} nodes live", nodes.size());
    }

    private static Map<String, String> kafkaEnv() {
        var env = new java.util.HashMap<String, String>();
        env.put("KAFKA_NODE_ID", "1");
        env.put("KAFKA_PROCESS_ROLES", "broker,controller");
        env.put("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093");
        env.put("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093");
        env.put("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092");
        env.put("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT");
        env.put("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
        env.put("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT");
        env.put("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        env.put("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
        env.put("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
        env.put("KAFKA_LOG_DIRS", "/tmp/kraft-combined-logs");
        env.put("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        env.put("CLUSTER_ID", "maestro-loan-cluster-001");
        return env;
    }

    private void createTopics() {
        String script = TOPICS.stream()
                .map(t -> "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 "
                        + "--create --if-not-exists --topic " + t
                        + " --partitions 3 --replication-factor 1")
                .reduce((a, b) -> a + " && " + b)
                .orElseThrow();
        try {
            var result = kafka.execInContainer("/bin/sh", "-c", script);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Kafka topic pre-creation failed: "
                        + result.getStderr() + result.getStdout());
            }
            log.info("[chaos] pre-created {} Kafka topics", TOPICS.size());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka topic pre-creation failed", e);
        }
    }

    /**
     * @param group consumer group id
     * @return the member count if the group is {@code Stable}, else 0
     */
    public int consumerGroupMembers(String group) {
        try {
            var result = kafka.execInContainer("/opt/kafka/bin/kafka-consumer-groups.sh",
                    "--bootstrap-server", "localhost:9092", "--describe", "--group", group, "--state");
            for (String line : result.getStdout().split("\n")) {
                if (line.contains("Stable")) {
                    String[] cols = line.trim().split("\\s+");
                    return Integer.parseInt(cols[cols.length - 1]);
                }
            }
        } catch (Exception e) {
            log.debug("[chaos] consumer-group probe {} failed: {}", group, e.toString());
        }
        return 0;
    }

    /**
     * Waits until {@code group} is {@code Stable} with at least {@code minMembers}
     * members (the {@code run-e2e.sh wait_for_consumer_group} check, ported).
     *
     * @param group      consumer group id
     * @param minMembers minimum stable members required
     * @param timeout    upper bound
     */
    public void awaitConsumerGroup(String group, int minMembers, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int last = 0;
        while (System.nanoTime() < deadline) {
            last = consumerGroupMembers(group);
            if (last >= minMembers) {
                return;
            }
            sleep(2000);
        }
        log.warn("[chaos] consumer group '{}' reached only {} of {} members within timeout",
                group, last, minMembers);
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> newNodeContainer(ManagedNode node) {
        NodeRole role = node.role;
        NodeRole.Service svc = role.service();
        int gen = node.generation;
        Path logFile = logDir.resolve(role.alias() + "-gen" + gen + ".log");
        node.logFiles.add(logFile);

        String jar = config.jarPaths().get(svc.jarKey());
        var container = new GenericContainer<>(DockerImageName.parse(NODE_IMAGE))
                .withNetwork(network)
                .withNetworkAliases(role.alias())
                .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app/app.jar")
                .withCommand("java", "-jar", "/app/app.jar")
                .withExposedPorts(NODE_PORT)
                .withEnv(nodeEnv(svc))
                .withLogConsumer(new FileLogConsumer(logFile))
                .waitingFor(Wait.forLogMessage(".*Started .*Application.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        return container;
    }

    private Map<String, String> nodeEnv(NodeRole.Service svc) {
        var env = new java.util.HashMap<String, String>();
        env.put("POSTGRES_HOST", "postgres");
        env.put("POSTGRES_PORT", "5432");
        env.put("POSTGRES_DB", svc.databaseName());
        env.put("POSTGRES_USER", "maestro");
        env.put("POSTGRES_PASSWORD", "maestro");
        env.put("KAFKA_BOOTSTRAP", "kafka:9092");
        env.put("VALKEY_HOST", "valkey");
        env.put("VALKEY_PORT", "6379");
        env.put("SERVER_PORT", String.valueOf(NODE_PORT));
        env.put("MAESTRO_LOCK_TYPE", "valkey");
        env.put("MAESTRO_SIGNAL_WAKE_RECHECK_INTERVAL", isoSeconds(config.wakeRecheckInterval()));
        env.put("JAVA_TOOL_OPTIONS", "-Xmx256m");
        // Sample timeouts (chaos-harness-design.md §3): long enough that a
        // prompt driver never times out a live path mid-chaos, short enough that
        // SIGNAL_TIMEOUT reaches terminal within the mode budget and child
        // underwriting workflows terminate within the drain SLA.
        String t = config.sampleTimeoutIso();
        env.put("MAESTRO_SAMPLE_DOC_TIMEOUT", t);
        env.put("MAESTRO_SAMPLE_SIGN_TIMEOUT", t);
        env.put("MAESTRO_SAMPLE_DECISION_TIMEOUT", t);
        env.put("MAESTRO_SAMPLE_GATE_TIMEOUT", isoSeconds(config.gateTimeout()));
        // Underwriter timeout matches the driver's decision deadline so a
        // chaos-delayed decision never loses to the child's own escalation
        // (which would turn a HAPPY path into a REJECTED loan); the senior
        // timeout stays short so a deliberately-unanswered child
        // (SIGNAL_TIMEOUT path) still reaches terminal (double-timeout
        // REJECTED) within the drain SLA.
        env.put("MAESTRO_SAMPLE_UNDERWRITER_TIMEOUT", t);
        env.put("MAESTRO_SAMPLE_SENIOR_TIMEOUT", "PT30S");
        // A consumer group's FIRST offset for a partition is 'earliest' so a
        // business event published before the group finished forming (node
        // boot / chaos-replacement) is consumed, not skipped — the design §3
        // Risk 1 hazard, removed at the source. Standard Spring relaxed
        // binding overrides the sample's application.yml value.
        env.put("SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET", "earliest");
        return env;
    }

    private static String isoSeconds(Duration d) {
        return "PT" + d.toSeconds() + "S";
    }

    // ------------------------------------------------------------- accessors

    /** @return a fresh data source for the given service's database (mapped port). */
    public PGSimpleDataSource dataSource(NodeRole.Service svc) {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{postgres.getHost()});
        ds.setPortNumbers(new int[]{postgres.getMappedPort(5432)});
        ds.setDatabaseName(svc.databaseName());
        ds.setUser("maestro");
        ds.setPassword("maestro");
        return ds;
    }

    /** @return the host-mapped base URL for the currently-live container of {@code role}. */
    public String baseUrl(NodeRole role) {
        GenericContainer<?> c = nodes.get(role).container;
        return "http://" + c.getHost() + ":" + c.getMappedPort(NODE_PORT);
    }

    /** @return the log files (across generations) captured for {@code role}. */
    public List<Path> logFiles(NodeRole role) {
        return List.copyOf(nodes.get(role).logFiles);
    }

    /** @return all node log files, across all roles and generations. */
    public List<Path> allLogFiles() {
        var all = new ArrayList<Path>();
        nodes.values().forEach(n -> all.addAll(n.logFiles));
        return all;
    }

    /** @return the roles currently unavailable (killed, paused, partitioned, stopping). */
    public Set<NodeRole> harassedRoles() {
        var s = java.util.EnumSet.noneOf(NodeRole.class);
        s.addAll(paused);
        s.addAll(partitioned);
        s.addAll(dead);
        return s;
    }

    // ------------------------------------------------------ Valkey / metrics

    /**
     * Runs {@code valkey-cli} inside the Valkey container.
     *
     * @param args cli arguments (e.g. {@code "INFO", "commandstats"})
     * @return the raw stdout, or empty string if the exec failed
     */
    public String valkeyCli(String... args) {
        try {
            var cmd = new ArrayList<String>(List.of("valkey-cli"));
            cmd.addAll(List.of(args));
            var result = valkey.execInContainer(cmd.toArray(String[]::new));
            return result.getStdout();
        } catch (Exception e) {
            log.warn("[chaos] valkey-cli {} failed: {}", List.of(args), e.toString());
            return "";
        }
    }

    // ----------------------------------------------------------- docker ops

    /** {@code docker kill -s SIGKILL} the live container for {@code role}. */
    public void kill(NodeRole role) {
        String id = nodes.get(role).container.getContainerId();
        docker.killContainerCmd(id).withSignal("SIGKILL").exec();
        dead.add(role);
        log.info("[chaos] KILL9 {} ({})", role, shortId(id));
    }

    /** Graceful {@code docker stop} (grace &gt; shutdown timeout) for {@code role}. */
    public void stopGraceful(NodeRole role) {
        String id = nodes.get(role).container.getContainerId();
        docker.stopContainerCmd(id).withTimeout(40).exec();
        dead.add(role);
        log.info("[chaos] STOP(graceful) {} ({})", role, shortId(id));
    }

    /** {@code docker pause} (cgroup freezer) the live container for {@code role}. */
    public void pause(NodeRole role) {
        docker.pauseContainerCmd(nodes.get(role).container.getContainerId()).exec();
        paused.add(role);
        log.info("[chaos] PAUSE {}", role);
    }

    /** {@code docker unpause} the container for {@code role}. */
    public void unpause(NodeRole role) {
        if (paused.remove(role)) {
            docker.unpauseContainerCmd(nodes.get(role).container.getContainerId()).exec();
            log.info("[chaos] UNPAUSE {}", role);
        }
    }

    /** {@code docker network disconnect} — isolate {@code role} from store+broker+lock. */
    public void partition(NodeRole role) {
        String id = nodes.get(role).container.getContainerId();
        docker.disconnectFromNetworkCmd().withContainerId(id)
                .withNetworkId(network.getId()).withForce(true).exec();
        partitioned.add(role);
        log.info("[chaos] PARTITION {}", role);
    }

    /** {@code docker network connect} — reattach {@code role} with its alias restored. */
    public void reconnect(NodeRole role) {
        if (partitioned.remove(role)) {
            String id = nodes.get(role).container.getContainerId();
            docker.connectToNetworkCmd().withContainerId(id).withNetworkId(network.getId())
                    .withContainerNetwork(new ContainerNetwork()
                            .withAliases(List.of(role.alias()))).exec();
            log.info("[chaos] RECONNECT {}", role);
        }
    }

    /** {@code docker pause}/{@code unpause} a backend container by name. */
    public void pauseBackend(String backend) {
        docker.pauseContainerCmd(backendId(backend)).exec();
        pausedBackends.add(backend);
        log.info("[chaos] BACKEND_OUTAGE pause {}", backend);
    }

    /** Unpause a previously paused backend container. */
    public void unpauseBackend(String backend) {
        docker.unpauseContainerCmd(backendId(backend)).exec();
        pausedBackends.remove(backend);
        log.info("[chaos] BACKEND_OUTAGE unpause {}", backend);
    }

    private volatile boolean benchmarkTailMode;

    /**
     * Marks the run as being in the Issue 12 benchmark tail: no chaos actions
     * run, and nodes stopped from here on are deliberate topology (the 6→3
     * vs-node-count measurement), not harassment — so the metrics sampler's
     * {@code chaosActive} column stays {@code false} and the tail's rows remain
     * usable as calm-window attribution points. {@link #liveNodeCount()} still
     * reports the reduced node count.
     */
    public void enterBenchmarkTailMode() {
        benchmarkTailMode = true;
    }

    /** @return true if any node is harassed or any backend is paused. */
    public boolean anyChaosActive() {
        return !benchmarkTailMode && (!harassedRoles().isEmpty() || !pausedBackends.isEmpty());
    }

    /** @return the count of nodes currently not harassed. */
    public int liveNodeCount() {
        return NodeRole.values().length - harassedRoles().size();
    }

    private String backendId(String backend) {
        return switch (backend) {
            case "postgres" -> postgres.getContainerId();
            case "kafka" -> kafka.getContainerId();
            case "valkey" -> valkey.getContainerId();
            default -> throw new IllegalArgumentException("unknown backend " + backend);
        };
    }

    /**
     * Starts a fresh container for {@code role} (new generation, new mapped port,
     * new log file), replacing a killed/stopped one. Updates the endpoint
     * registry synchronously (Risk 5) before returning.
     */
    public void replace(NodeRole role) {
        ManagedNode node = nodes.get(role);
        node.generation++;
        node.container = newNodeContainer(node);
        node.container.start();
        dead.remove(role);
        awaitNodeHealthy(role, Duration.ofMinutes(3));
        log.info("[chaos] REPLACED {} -> generation {} at {}", role, node.generation, baseUrl(role));
    }

    private String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(12, id.length()));
    }

    // --------------------------------------------------------------- heal-all

    /**
     * Heal-all (chaos-harness-design.md §4 stop-before-verify): unpause every
     * paused backend AND node, reconnect everything partitioned, replace
     * anything dead, then wait for all six roles healthy. Idempotent. Backends
     * are healed first and explicitly — if the controller thread dies mid-
     * {@code BACKEND_OUTAGE} (the soak-failure shape, report §10), the paused
     * backend would otherwise stay frozen forever and every later DB/Valkey
     * access would fail while looking like a node problem.
     */
    public void healAll() {
        log.info("[chaos] heal-all begin");
        for (String backend : Set.copyOf(pausedBackends)) {
            try {
                unpauseBackend(backend);
            } catch (RuntimeException e) {
                log.warn("[chaos] heal-all: unpause of backend {} failed: {}", backend, e.toString());
            }
        }
        Set.copyOf(paused).forEach(this::unpause);
        Set.copyOf(partitioned).forEach(this::reconnect);
        Set.copyOf(dead).forEach(this::replace);
        awaitAllNodesHealthy(Duration.ofMinutes(3));
        log.info("[chaos] heal-all complete: all nodes healthy");
    }

    // -------------------------------------------------------------- readiness

    private void awaitAllNodesHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (NodeRole role : NodeRole.values()) {
            long remain = deadline - System.nanoTime();
            awaitNodeHealthy(role, Duration.ofNanos(Math.max(remain, 0)));
        }
    }

    private void awaitNodeHealthy(NodeRole role, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isHttpUp(role)) {
                return;
            }
            sleep(500);
        }
        throw new IllegalStateException("Node " + role + " did not become HTTP-ready");
    }

    /** @return true if the live container for {@code role} answers its readiness probe. */
    public boolean isHttpUp(NodeRole role) {
        String path = switch (role.service()) {
            case LOAN_APPLICATION -> "/applications/__probe__";
            case VERIFICATION_GATEWAY -> "/webhooks/credit/__probe__";
            case UNDERWRITING -> "/underwriting/pending";
        };
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl(role) + path))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void verifyValkeyLockMode() {
        // The starter logs the effective DistributedLock implementation at boot;
        // assert every loan node selected the Valkey lock (chaos v1 is
        // Valkey-only, ruling Q3).
        for (NodeRole role : List.of(NodeRole.LOAN_A, NodeRole.LOAN_B)) {
            Path logFile = nodes.get(role).logFiles.get(0);
            boolean valkeyLock = fileContains(logFile, "Valkey")
                    && fileContains(logFile, "DistributedLock");
            if (!valkeyLock) {
                log.warn("[chaos] could not confirm Valkey lock mode from {} log (continuing)", role);
            }
        }
    }

    private boolean fileContains(Path file, String needle) {
        try {
            return Files.exists(file) && Files.readString(file).contains(needle);
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        nodes.values().forEach(n -> quietStop(n.container));
        quietStop(valkey);
        quietStop(kafka);
        quietStop(postgres);
        if (network != null) {
            try {
                network.close();
            } catch (Exception ignore) {
                // Ryuk reaps the network on JVM exit.
            }
        }
    }

    private void quietStop(GenericContainer<?> c) {
        if (c != null) {
            try {
                c.stop();
            } catch (Exception ignore) {
                // best effort; Ryuk backstops.
            }
        }
    }

    /** Streams container stdout/stderr to a host file, appending per frame. */
    private static final class FileLogConsumer implements java.util.function.Consumer<OutputFrame> {
        private final Path file;

        FileLogConsumer(Path file) {
            this.file = file;
        }

        @Override
        public void accept(OutputFrame frame) {
            try {
                Files.writeString(file, frame.getUtf8String(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // Never fail the run on a log write.
            }
        }
    }
}
