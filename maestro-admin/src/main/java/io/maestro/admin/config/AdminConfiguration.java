package io.maestro.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Central configuration class for the Maestro Admin dashboard.
 *
 * <p>Enables binding of {@link AdminProperties} from the application
 * configuration under the {@code maestro.admin.*} namespace.
 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfiguration {
}
