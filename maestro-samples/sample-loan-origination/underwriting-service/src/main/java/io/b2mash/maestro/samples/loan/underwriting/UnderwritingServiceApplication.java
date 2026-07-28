package io.b2mash.maestro.samples.loan.underwriting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Underwriting service: auto-assessment rules plus a human decision
 * queue with timeout escalation to a senior underwriter.
 */
@SpringBootApplication
public class UnderwritingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnderwritingServiceApplication.class, args);
    }
}
