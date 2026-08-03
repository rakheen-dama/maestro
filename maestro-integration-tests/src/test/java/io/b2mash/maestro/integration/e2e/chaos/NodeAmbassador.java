package io.b2mash.maestro.integration.e2e.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The test JVM's stable door into the chaos network: one small {@code socat}
 * container that publishes one host port per node role and forwards each to
 * {@code <alias>:<targetPort>} inside the Docker network.
 *
 * <h2>Why this exists</h2>
 * <p>A node's own published port cannot be used, because it does not survive
 * the harness's own {@code PARTITION} action. Docker programs a container's
 * published-port NAT exactly once — when the container starts, against the
 * address it holds on the network it started on. {@code docker network
 * disconnect --force} releases that address; {@code docker network connect}
 * builds a <em>new</em> endpoint and does not re-publish the port. A node that
 * comes back on a different address is therefore unreachable through its
 * mapped host port <em>for the rest of the run</em>, and there is no Docker API
 * to re-publish a port on a running container — so re-resolving
 * {@code getMappedPort()} cannot recover it either. That is precisely how
 * nightly run 30731056376 died: {@code awaitAllNodesHealthy} timed out on a
 * VERIFY_B that was alive and processing workflows the whole time.
 *
 * <p>The ambassador is never harassed (it is not a {@link NodeRole} and not a
 * backend), so its own published ports are programmed once and stay valid for
 * the life of the run. Its forwarding target is a <em>name</em>, resolved by
 * Docker's embedded DNS on each new connection, so it automatically follows:
 *
 * <ul>
 *   <li>a {@code PARTITION}/{@code RECONNECT} pair — including one that returns
 *       the node on a different address;</li>
 *   <li>a {@code KILL9}/{@code ROLLING_RESTART} replacement, which starts a
 *       brand-new container reusing the same alias.</li>
 * </ul>
 *
 * <p>Fault semantics are unchanged: while a node is disconnected its alias does
 * not resolve, the forward fails, and the harness's probe correctly reports it
 * down. The ambassador restores reachability only when the node is genuinely
 * back on the network.
 *
 * <p>Because the endpoint for a role is fixed for the whole run, no caller can
 * hold a stale host/port for a node — the class of bug this replaces.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable after {@link #start()}; {@link #baseUrl(String)} is safe for
 * concurrent use by the driver, sampler and checker.
 */
final class NodeAmbassador implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeAmbassador.class);

    /** Same pin Testcontainers' own {@code SocatContainer} uses. */
    private static final DockerImageName IMAGE = DockerImageName.parse("alpine/socat:1.7.4.3-r0");

    private static final int FIRST_LISTENER_PORT = 9001;

    private final int targetPort;
    /** alias -&gt; in-container listener port, in declaration order. */
    private final Map<String, Integer> listeners = new LinkedHashMap<>();
    private final GenericContainer<?> container;

    /**
     * @param network    the chaos network the nodes live on
     * @param aliases    the node network aliases to expose, one listener each
     * @param targetPort the in-container port every node listens on
     */
    @SuppressWarnings("resource")
    NodeAmbassador(Network network, List<String> aliases, int targetPort) {
        this.targetPort = targetPort;
        int port = FIRST_LISTENER_PORT;
        for (String alias : aliases) {
            listeners.put(alias, port++);
        }
        this.container = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("ambassador")
                .withExposedPorts(listeners.values().toArray(Integer[]::new))
                // The image's entrypoint is socat itself; one forwarder per node
                // needs a shell to run them side by side.
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                .withCommand("-c", script())
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * {@code fork} makes socat resolve the target name per accepted connection,
     * which is the whole point — the listener outlives every address change
     * behind the alias.
     */
    private String script() {
        return listeners.entrySet().stream()
                .map(e -> "socat TCP-LISTEN:" + e.getValue() + ",fork,reuseaddr"
                        + " TCP:" + e.getKey() + ":" + targetPort + " &")
                .collect(Collectors.joining(" ")) + " wait";
    }

    /** Starts the forwarder. Safe to call before any node exists. */
    void start() {
        container.start();
        log.info("[chaos] ambassador up at {}: {}", container.getHost(),
                listeners.keySet().stream()
                        .map(a -> a + "->" + container.getMappedPort(listeners.get(a)))
                        .collect(Collectors.joining(", ")));
    }

    /**
     * @param alias a node network alias passed to the constructor
     * @return the run-stable base URL the test JVM uses to reach that node
     * @throws IllegalArgumentException if the alias was never registered
     */
    String baseUrl(String alias) {
        Integer listener = listeners.get(alias);
        if (listener == null) {
            throw new IllegalArgumentException("no ambassador listener for alias '" + alias + "'");
        }
        return "http://" + container.getHost() + ":" + container.getMappedPort(listener);
    }

    @Override
    public void close() {
        try {
            container.stop();
        } catch (RuntimeException ignore) {
            // best effort; Ryuk backstops.
        }
    }
}
