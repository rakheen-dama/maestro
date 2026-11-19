package io.b2mash.maestro.consumersmoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal service that consumes Maestro as a published dependency: the BOM,
 * the starter, the Postgres store and the Kafka transport, resolved from a
 * Maven repository, with the Spring Boot platform imported by this build
 * itself. It has one workflow ({@link ShipmentWorkflow}), its activities
 * ({@link ShipmentActivities}) and one {@code @MaestroSignalListener}
 * ({@link DispatchListener}).
 */
@SpringBootApplication
public class ConsumerSmokeApplication {

    /**
     * Starts the service.
     *
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ConsumerSmokeApplication.class, args);
    }
}
