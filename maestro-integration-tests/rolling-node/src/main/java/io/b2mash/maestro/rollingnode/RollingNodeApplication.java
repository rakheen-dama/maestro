package io.b2mash.maestro.rollingnode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One Maestro node of the rolling-upgrade test: a Spring Boot application on
 * the starter, the Postgres store and lock, and nothing else, exactly as a
 * service deploys Maestro.
 *
 * <h2>Built twice from one source</h2>
 * <p>This source set is compiled against this build's modules and, separately,
 * against each previous release's published artifacts
 * ({@code rolling-node/build.gradle.kts}). The test then runs one node of each
 * build as its own JVM against one database. Because both builds run this same
 * source, it may use only API present in the oldest supported release: the
 * workflow-author API of {@code maestro-core} (annotations,
 * {@code WorkflowContext}), the starter's {@code MaestroClient}, and the
 * {@code EngineObserver} SPI. Never reach into engine internals here.
 *
 * <h2>Control</h2>
 * <p>Configured entirely by command-line properties (the test passes the
 * datasource, {@code maestro.*} and {@code rolling.*} values). Once ready it
 * prints {@code ROLLING-NODE READY port=<n>} and serves {@link NodeControlServer}
 * on that port. It stops on SIGTERM through Spring's shutdown hook, i.e. the
 * engine's graceful shutdown, as a deploy stops a node.
 */
@SpringBootApplication
public class RollingNodeApplication {

    /**
     * Starts the node.
     *
     * @param args Spring Boot command-line properties
     */
    public static void main(String[] args) {
        SpringApplication.run(RollingNodeApplication.class, args);
    }
}
