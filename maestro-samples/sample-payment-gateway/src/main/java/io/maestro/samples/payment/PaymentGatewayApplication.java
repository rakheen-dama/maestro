package io.maestro.samples.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Payment gateway proxy demonstrating durable retries
 * and cross-service workflow coordination.
 */
@SpringBootApplication
public class PaymentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentGatewayApplication.class, args);
    }
}
