package io.b2mash.maestro.lock.postgres.config;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import io.b2mash.maestro.lock.postgres.PostgresLockCleaner;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Auto-configuration for PostgreSQL-based distributed locking.
 *
 * <p>Activates when:
 * <ul>
 *   <li>The PostgreSQL JDBC driver is on the classpath</li>
 *   <li>{@code maestro.lock.type} is explicitly set to {@code "postgres"}</li>
 * </ul>
 *
 * <p>Creates the following beans:
 * <ul>
 *   <li>{@link PostgresDistributedLock} — the {@link DistributedLock} SPI implementation</li>
 *   <li>{@link PostgresLockCleaner} — expired lock row cleanup</li>
 * </ul>
 *
 * <p>The {@link DataSource} is expected to already be present in the
 * Spring context (e.g., via Spring Boot's DataSource auto-configuration).
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} to allow
 * user overrides.
 *
 * @see PostgresDistributedLock
 * @see PostgresLockCleaner
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnProperty(prefix = "maestro.lock", name = "type", havingValue = "postgres")
public class PostgresLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public PostgresDistributedLock postgresDistributedLock(DataSource dataSource) {
        return new PostgresDistributedLock(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(PostgresLockCleaner.class)
    public PostgresLockCleaner postgresLockCleaner(DataSource dataSource) {
        return new PostgresLockCleaner(dataSource);
    }
}
