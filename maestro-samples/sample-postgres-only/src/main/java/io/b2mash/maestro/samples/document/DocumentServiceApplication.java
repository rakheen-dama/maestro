package io.b2mash.maestro.samples.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Document approval service demonstrating durable workflows with Postgres only.
 *
 * <p>This sample requires no external infrastructure beyond PostgreSQL. Messaging and
 * distributed locking are handled by the Postgres-backed SPI implementations, making
 * it the simplest possible Maestro deployment.
 */
@SpringBootApplication
public class DocumentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}
