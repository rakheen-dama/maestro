package io.b2mash.maestro.integration.e2e.chaos;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
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

/**
 * EXPERIMENT (temporary, not a regression test): does a container's published
 * host port survive {@code docker network disconnect --force} followed by
 * {@code docker network connect}, and does {@code getMappedPort()} re-resolve?
 *
 * <p>Answers the question the fix depends on: can the harness simply re-resolve
 * the mapped port after a RECONNECT, or must the host reach nodes by another
 * route entirely?
 *
 * <p>Case A reconnects letting Docker's IPAM choose the address (what the
 * harness does today). Case B pins a deliberately different address, which is
 * what a busy network produces once the freed address has been taken.
 */
@Tag("e2e")
@DisplayName("EXPERIMENT: published port across disconnect --force + connect")
class PublishedPortReconnectExperimentIT {

    private static final Logger log = LoggerFactory.getLogger(PublishedPortReconnectExperimentIT.class);

    private static final DockerImageName SOCAT = DockerImageName.parse("alpine/socat:1.7.4.3-r0");
    private static final int NODE_PORT = 8080;
    private static final String ALIAS = "exp-node";
    private static final String SUBNET = "10.174.244.0/24";
    private static final String GATEWAY = "10.174.244.1";
    private static final String PINNED_IP = "10.174.244.99";

    private final DockerClient docker = DockerClientFactory.instance().client();

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("case A: reconnect with a Docker-chosen address")
    void caseA_dockerChosenAddress() throws Exception {
        run("A", false);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("case B: reconnect on a different address")
    void caseB_differentAddress() throws Exception {
        run("B", true);
    }

    private void run(String label, boolean pinDifferentIp) throws Exception {
        try (Network network = subnettedNetwork();
             GenericContainer<?> node = echoNode(network)) {
            node.start();

            String host = node.getHost();
            int portBefore = node.getMappedPort(NODE_PORT);
            String ipBefore = containerIp(node);
            log.info("[exp{}] BEFORE: ip={} getMappedPort={} dockerNat={} reachable={}",
                    label, ipBefore, portBefore, dockerNat(node),
                    reachable(host, portBefore));

            docker.disconnectFromNetworkCmd()
                    .withContainerId(node.getContainerId())
                    .withNetworkId(network.getId())
                    .withForce(true)
                    .exec();
            log.info("[exp{}] PARTITIONED: ip={} getMappedPort={} dockerNat={} reachable={}",
                    label, containerIp(node), node.getMappedPort(NODE_PORT), dockerNat(node),
                    reachable(host, portBefore));

            var connect = docker.connectToNetworkCmd()
                    .withContainerId(node.getContainerId())
                    .withNetworkId(network.getId());
            var cn = new ContainerNetwork().withAliases(List.of(ALIAS));
            if (pinDifferentIp) {
                cn = cn.withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(PINNED_IP));
            }
            connect.withContainerNetwork(cn).exec();

            String ipAfter = containerIp(node);
            // Testcontainers caches containerInfo at start; ask it again anyway,
            // and independently ask Docker for the live NAT table.
            int portAfterCached = node.getMappedPort(NODE_PORT);
            String natAfter = dockerNat(node);
            log.info("[exp{}] RECONNECTED: ipBefore={} ipAfter={} ipChanged={} "
                            + "getMappedPortBefore={} getMappedPortAfter={} portNumberChanged={} dockerNat={}",
                    label, ipBefore, ipAfter, !ipBefore.equals(ipAfter),
                    portBefore, portAfterCached, portBefore != portAfterCached, natAfter);

            boolean alive = reachableWithin(host, portAfterCached, 20_000);
            log.info("[exp{}] VERDICT: publishedPortReachableAfterReconnect={} (host={} port={})",
                    label, alive, host, portAfterCached);
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

    @SuppressWarnings("resource")
    private GenericContainer<?> echoNode(Network network) {
        return new GenericContainer<>(SOCAT)
                .withNetwork(network)
                .withNetworkAliases(ALIAS)
                .withExposedPorts(NODE_PORT)
                .withCommand("TCP-LISTEN:" + NODE_PORT + ",fork,reuseaddr", "EXEC:/bin/cat");
    }

    /** What Docker itself currently advertises as the host binding for the port. */
    private String dockerNat(GenericContainer<?> c) {
        var bindings = docker.inspectContainerCmd(c.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings();
        Ports.Binding[] b = bindings.get(ExposedPort.tcp(NODE_PORT));
        if (b == null || b.length == 0) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder();
        for (Ports.Binding binding : b) {
            sb.append(binding.getHostIp()).append(':').append(binding.getHostPortSpec()).append(' ');
        }
        return sb.toString().trim();
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

    private static boolean reachableWithin(String host, int port, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (reachable(host, port)) {
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
