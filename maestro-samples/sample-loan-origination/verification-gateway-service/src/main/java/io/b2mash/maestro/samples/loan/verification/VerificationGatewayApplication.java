package io.b2mash.maestro.samples.loan.verification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Verification gateway: simulated credit, employment, and appraisal
 * verifiers with webhook-style out-of-order result delivery.
 */
@SpringBootApplication
public class VerificationGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerificationGatewayApplication.class, args);
    }
}
