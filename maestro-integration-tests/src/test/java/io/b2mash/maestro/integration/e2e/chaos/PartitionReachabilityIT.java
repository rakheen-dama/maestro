package io.b2mash.maestro.integration.e2e.chaos;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the chaos harness's node-reachability contract:
 * <strong>after a {@code PARTITION} is healed by {@code RECONNECT}, the test
 * JVM must be able to reach that node again at the endpoint the harness hands
 * out</strong> ({@code ChaosCluster.baseUrl}).
 *
 * <p>The nightly {@code E2E (loan-origination)} run 30731056376 died on exactly
 * this: {@code healAll()}'s {@code awaitAllNodesHealthy(3 min)} threw
 * {@code IllegalStateException("Node VERIFY_B did not become HTTP-ready")} at
 * 181.9 s, while VERIFY_B was demonstrably alive — still processing workflows
 * over Kafka and Postgres. The node was fine; the harness's route to it was
 * not.
 *
 * <h2>Why the published-port route cannot survive a reconnect</h2>
 * <p>Docker programs a container's published-port NAT once, when the container
 * starts, against the container IP it held on the network it was started on.
 * {@code docker network disconnect --force} releases that address;
 * {@code docker network connect} creates a <em>new</em> endpoint and does
 * <em>not</em> re-publish the port. Whenever the reattached container comes
 * back on a different address — which is what happens on a busy network, and
 * what happened to VERIFY_B in CI — the old mapping is a black hole:
 * {@code docker port} keeps reporting the same host port, and nothing is
 * listening behind it. There is no Docker API to re-publish a port on a
 * running container, so no amount of re-resolving {@code getMappedPort()} can
 * recover it.
 *
 * <p>The IP change is forced explicitly here (the reconnect pins a different
 * address in the network's subnet) so the failure is deterministic on every
 * platform, instead of depending on how a particular Docker IPAM happens to
 * recycle the freed address.
 */
@Tag("e2e")
@DisplayName("Chaos harness: a reconnected node is reachable again")
class PartitionReachabilityIT {

    private static final Logger log = LoggerFactory.getLogger(PartitionReachabilityIT.class);

    /** Same image the ambassador uses; here it plays the part of a workload node. */
    private static final DockerImageName SOCAT = DockerImageName.parse("alpine/socat:1.7.4.3-r0");

    private static final int NODE_PORT = 8080;
    private static final String ALIAS = "verify-b";

    /** A subnet the test owns, so the reconnect can pin a deliberately different address. */
    private static final String SUBNET = "10.173.244.0/24";
    private static final String GATEWAY = "10.173.244.1";
    private static final String RECONNECT_IP = "10.173.244.99";

    private final DockerClient docker = DockerClientFactory.instance().client();

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("the harness endpoint for a node still works after PARTITION + RECONNECT")
    void harnessEndpointSurvivesPartitionAndReconnect() throws Exception {
        try (Network network = subnettedNetwork();
             NodeAmbassador ambassador = new NodeAmbassador(network, List.of(ALIAS), NODE_PORT);
             GenericContainer<?> node = echoNode(network)) {

            // Started before any node exists, exactly as ChaosCluster.start() does.
            ambassador.start();
            node.start();

            // ---- the endpoint the harness hands out (ChaosCluster.baseUrl) ----
            var endpoint = Endpoint.of(ambassador.baseUrl(ALIAS));
            // ...and the route the harness used to hand out, kept only to show
            // it is the thing that breaks.
            String directHost = node.getHost();
            int directPort = node.getFirstMappedPort();
            log.info("[repro] node up: alias={} ip={} harness endpoint={} direct(published)={}:{}",
                    ALIAS, containerIp(node), endpoint, directHost, directPort);

            assertTrue(reachable(endpoint), "precondition: the node must be reachable before chaos");
            assertTrue(reachable(new Endpoint(directHost, directPort)),
                    "precondition: the published port works before chaos");

            // ---- PARTITION (ChaosCluster.partition) ----
            docker.disconnectFromNetworkCmd()
                    .withContainerId(node.getContainerId())
                    .withNetworkId(network.getId())
                    .withForce(true)
                    .exec();
            log.info("[repro] PARTITION applied; ip now '{}'", containerIp(node));
            assertFalse(reachable(endpoint),
                    "a partitioned node must NOT be reachable — otherwise the fault is not real, "
                            + "and the harness would report a still-isolated node as healed");

            // ---- RECONNECT (ChaosCluster.reconnect), on a different address ----
            docker.connectToNetworkCmd()
                    .withContainerId(node.getContainerId())
                    .withNetworkId(network.getId())
                    .withContainerNetwork(new ContainerNetwork()
                            .withAliases(List.of(ALIAS))
                            .withIpamConfig(new ContainerNetwork.Ipam()
                                    .withIpv4Address(RECONNECT_IP)))
                    .exec();
            log.info("[repro] RECONNECT applied; ip now '{}'; docker still advertises host port {}",
                    containerIp(node), directPort);

            assertTrue(reachableWithin(endpoint, 30_000),
                    "after RECONNECT the harness must be able to reach the node again at "
                            + endpoint + " — this is the heal gate that failed in "
                            + "nightly run 30731056376 with 'Node VERIFY_B did not become HTTP-ready'");

            // The defect itself, recorded rather than asserted: the node's own
            // published port is now a black hole and stays one. Docker offers no
            // way to re-publish it, which is why the ambassador exists.
            log.info("[repro] node's own published port {}:{} after RECONNECT: reachable={}",
                    directHost, directPort, reachable(new Endpoint(directHost, directPort)));
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("the harness endpoint follows a node replaced by a fresh container")
    void harnessEndpointFollowsAReplacement() throws Exception {
        try (Network network = subnettedNetwork();
             NodeAmbassador ambassador = new NodeAmbassador(network, List.of(ALIAS), NODE_PORT)) {

            ambassador.start();
            var endpoint = Endpoint.of(ambassador.baseUrl(ALIAS));

            try (GenericContainer<?> gen0 = echoNode(network)) {
                gen0.start();
                assertTrue(reachable(endpoint), "generation 0 must be reachable");
                // KILL9 + replace(): the old container dies, a fresh one takes
                // the alias. The endpoint must not move.
                docker.killContainerCmd(gen0.getContainerId()).withSignal("SIGKILL").exec();
            }

            try (GenericContainer<?> gen1 = echoNode(network)) {
                gen1.start();
                log.info("[repro] replacement up at ip={}; endpoint unchanged: {}",
                        containerIp(gen1), endpoint);
                assertTrue(reachableWithin(endpoint, 30_000),
                        "after a replacement the harness endpoint " + endpoint
                                + " must reach the new container — the endpoint is run-stable");
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("a genuinely dead node still fails the probe through the ambassador")
    void aDeadNodeIsStillReportedDown() throws Exception {
        try (Network network = subnettedNetwork();
             NodeAmbassador ambassador = new NodeAmbassador(network, List.of(ALIAS), NODE_PORT)) {

            ambassador.start();
            var endpoint = Endpoint.of(ambassador.baseUrl(ALIAS));

            // No node has ever existed behind the alias.
            assertFalse(reachable(endpoint),
                    "with nothing behind the alias the probe must fail — an ambassador that "
                            + "reported healthy here would turn a real outage into a false green");

            try (GenericContainer<?> node = echoNode(network)) {
                node.start();
                assertTrue(reachable(endpoint), "the node is up: the probe must succeed");

                docker.killContainerCmd(node.getContainerId()).withSignal("SIGKILL").exec();
                assertTrue(unreachableWithin(endpoint, 30_000),
                        "after kill -9 the probe must fail again through the ambassador");
            }

            // Why the harness's probe must require a *response*, not merely a
            // connect: socat's listener stays bound, so a bare TCP connect can
            // be accepted and then immediately closed with the backend gone.
            // ChaosCluster.isHttpUp sends a real HTTP request and requires a
            // status line, which is the round-trip this probe models.
            log.info("[repro] bare TCP connect with the backend dead: accepted={}",
                    connectSucceeds(endpoint));
        }
    }

    /** A host-side {@code host:port} the test probes over raw TCP. */
    private record Endpoint(String host, int port) {
        static Endpoint of(String baseUrl) {
            var uri = java.net.URI.create(baseUrl);
            return new Endpoint(uri.getHost(), uri.getPort());
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    // ------------------------------------------------------------------ fixture

    private Network subnettedNetwork() {
        return Network.builder()
                .createNetworkCmdModifier(cmd -> cmd.withIpam(
                        new com.github.dockerjava.api.model.Network.Ipam()
                                .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config()
                                        .withSubnet(SUBNET)
                                        .withGateway(GATEWAY))))
                .build();
    }

    /**
     * A stand-in workload node: a line echo server on {@link #NODE_PORT}. TCP
     * reachability is exactly what the harness's HTTP probe ultimately depends
     * on, and an echo keeps the fixture free of an HTTP server image.
     */
    @SuppressWarnings("resource")
    private GenericContainer<?> echoNode(Network network) {
        return new GenericContainer<>(SOCAT)
                .withNetwork(network)
                .withNetworkAliases(ALIAS)
                .withExposedPorts(NODE_PORT)
                .withCommand("TCP-LISTEN:" + NODE_PORT + ",fork,reuseaddr", "EXEC:/bin/cat");
    }

    private String containerIp(GenericContainer<?> c) {
        var networks = docker.inspectContainerCmd(c.getContainerId()).exec()
                .getNetworkSettings().getNetworks();
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst().orElse("<none>");
    }

    // ------------------------------------------------------------------- probe

    private static boolean reachableWithin(Endpoint endpoint, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (reachable(endpoint)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** @return true if {@code endpoint} stops answering within {@code millis}. */
    private static boolean unreachableWithin(Endpoint endpoint, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!reachable(endpoint)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** A bare connect, with no round-trip — what the probe must NOT rely on. */
    private static boolean connectSucceeds(Endpoint endpoint) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean reachable(Endpoint endpoint) {
        return reachable(endpoint.host(), endpoint.port());
    }

    /** @return true if a TCP round-trip to {@code host:port} completes within 2s. */
    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write("ping\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[5];
            int n = in.read(buf);
            return n > 0 && new String(buf, 0, n, StandardCharsets.UTF_8).startsWith("ping");
        } catch (IOException e) {
            return false;
        }
    }
}
