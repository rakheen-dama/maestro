package io.maestro.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Maestro Admin Dashboard — standalone workflow monitoring application.
 */
@SpringBootApplication
public class MaestroAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaestroAdminApplication.class, args);
    }
}
