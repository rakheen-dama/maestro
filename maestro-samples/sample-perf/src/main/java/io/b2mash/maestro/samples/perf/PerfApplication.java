package io.b2mash.maestro.samples.perf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Load-test target for the k6 harness under {@code perf/}: two knob-driven
 * workflows ({@code perf-chain}, {@code perf-park}) behind a small HTTP surface,
 * running Maestro's true defaults. Not published.
 */
@SpringBootApplication
public class PerfApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PerfApplication.class, args);
    }
}
