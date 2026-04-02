package io.b2mash.maestro.samples.rabbitmqorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Order service demonstrating durable workflows with RabbitMQ messaging
 * and Postgres-based distributed locking.
 */
@SpringBootApplication
public class RabbitMqOrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitMqOrderServiceApplication.class, args);
    }
}
