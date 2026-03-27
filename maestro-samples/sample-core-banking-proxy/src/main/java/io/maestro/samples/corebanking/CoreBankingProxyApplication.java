package io.maestro.samples.corebanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Core banking proxy demonstrating durable retries,
 * saga compensation, and external API integration.
 */
@SpringBootApplication
public class CoreBankingProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreBankingProxyApplication.class, args);
    }
}
