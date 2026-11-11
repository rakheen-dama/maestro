package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.spring.config.MaestroProperties;
import io.b2mash.maestro.store.postgres.MaestroDataSource;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleaner;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleanerScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Auto-configuration for PostgreSQL-based distributed locking — Maestro's
 * (only) lock backend, shipped with the store.
 *
 * <p>Activates when:
 * <ul>
 *   <li>The PostgreSQL JDBC driver is on the classpath</li>
 *   <li>{@code maestro.enabled} is {@code true} (default)</li>
 *   <li>{@code maestro.lock.type} is {@code "postgres"} (default); any other
 *       value fails startup in {@link MaestroAutoConfiguration}</li>
 *   <li>A {@link DataSource} bean exists</li>
 * </ul>
 *
 * <p>Creates the following beans:
 * <ul>
 *   <li>{@link PostgresDistributedLock} — the {@link DistributedLock} SPI implementation</li>
 *   <li>{@link PostgresLockCleaner} — expired lock row cleanup</li>
 *   <li>{@link PostgresLockCleanerScheduler} — runs the cleaner every
 *       {@code maestro.lock.cleanup-interval} (default 10 minutes); stopped
 *       when the context closes</li>
 * </ul>
 *
 * <p>Ordered after {@link MaestroAutoConfiguration} so that
 * {@link MaestroProperties} is bound; the engine's {@code @Nullable
 * DistributedLock} parameter still receives the lock because bean
 * <em>definitions</em> are resolved before any bean is instantiated.
 *
 * <p>The lock and the cleaner use the same {@code DataSource} as the store:
 * the {@link MaestroDataSource @MaestroDataSource}-qualified one if declared,
 * otherwise the application's single (or {@code @Primary}) one.
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} to allow
 * user overrides.
 *
 * @see PostgresDistributedLock
 * @see PostgresLockCleaner
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnBean(DataSource.class)
// Audit F8: maestro.enabled=false is documented as the master kill-switch
// (see MaestroAutoConfiguration). See PostgresLockAutoConfigurationMaestroDisabledTest.
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "maestro.lock", name = "type", havingValue = "postgres", matchIfMissing = true)
public class PostgresLockAutoConfiguration {

    private static final String CLEANUP_INTERVAL_PROPERTY = "maestro.lock.cleanup-interval";

    private static final String DEFAULT_CLEANUP_INTERVAL = "10m";

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public PostgresDistributedLock postgresDistributedLock(
            @MaestroDataSource ObjectProvider<DataSource> maestroDataSource, ObjectProvider<DataSource> dataSource) {
        return new PostgresDistributedLock(MaestroDataSources.resolve(maestroDataSource, dataSource));
    }

    @Bean
    @ConditionalOnMissingBean(PostgresLockCleaner.class)
    public PostgresLockCleaner postgresLockCleaner(
            @MaestroDataSource ObjectProvider<DataSource> maestroDataSource, ObjectProvider<DataSource> dataSource) {
        return new PostgresLockCleaner(MaestroDataSources.resolve(maestroDataSource, dataSource));
    }

    /**
     * Schedules the cleaner. Reads the interval from the {@link Environment}
     * rather than {@link MaestroProperties} because that bean only exists when
     * the engine itself activated — and the lock tables deserve cleaning even
     * when a host wires the lock without the engine. The value accepts Boot's
     * duration syntax ({@code 10m}, {@code PT10M}, {@code 600000}).
     *
     * @param cleaner the cleaner to run
     * @param env     the environment {@code maestro.lock.cleanup-interval} is read from
     * @return the started scheduler; closed with the context
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(PostgresLockCleanerScheduler.class)
    public PostgresLockCleanerScheduler postgresLockCleanerScheduler(PostgresLockCleaner cleaner, Environment env) {
        var raw = env.getProperty(CLEANUP_INTERVAL_PROPERTY, DEFAULT_CLEANUP_INTERVAL);
        Duration interval;
        try {
            interval = DurationStyle.detectAndParse(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(CLEANUP_INTERVAL_PROPERTY + " '" + raw + "' is not a duration", e);
        }
        var scheduler = new PostgresLockCleanerScheduler(cleaner, interval);
        scheduler.start();
        return scheduler;
    }
}
