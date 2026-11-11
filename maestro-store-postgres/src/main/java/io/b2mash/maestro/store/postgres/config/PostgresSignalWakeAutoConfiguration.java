package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.spring.config.MaestroProperties;
import io.b2mash.maestro.store.postgres.MaestroDataSource;
import io.b2mash.maestro.store.postgres.notify.PostgresSignalNotifier;
import io.b2mash.maestro.store.postgres.notify.PostgresWakeListener;
import io.b2mash.maestro.store.postgres.notify.WakeChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Auto-configuration for the cross-node signal wake over PostgreSQL
 * {@code LISTEN}/{@code NOTIFY}.
 *
 * <p>Without a {@link SignalNotifier}, a signal ingested on one node reaches a
 * workflow parked on another only on the next
 * {@code maestro.signal.wake-recheck-interval} store re-read (30 s by
 * default). This configuration gives every Postgres deployment an instant
 * wake, using the database it already has.
 *
 * <p>Activates when:
 * <ul>
 *   <li>pgjdbc is on the classpath ({@code org.postgresql.PGConnection})</li>
 *   <li>The engine is active (a {@link WorkflowExecutor} bean exists)</li>
 *   <li>{@code maestro.enabled} is {@code true} (default)</li>
 *   <li>{@code maestro.signal.wake-notify.enabled} is {@code true} (default).
 *       Set it to {@code false} behind a connection pooler in transaction
 *       pooling mode (PgBouncer), where a session-level {@code LISTEN}
 *       cannot work; the store re-read then bounds wake latency.</li>
 * </ul>
 *
 * <p>Creates the following beans:
 * <ul>
 *   <li>{@link PostgresWakeListener} — this node's one {@code LISTEN}
 *       session on {@link WakeChannel#channelFor the service's channel};
 *       started here, closed with the context. It holds one pool connection
 *       for the application's lifetime — from the
 *       {@link MaestroDataSource @MaestroDataSource}-qualified pool when the
 *       application declares one, otherwise from its single (or
 *       {@code @Primary}) {@code DataSource}.</li>
 *   <li>{@link PostgresSignalNotifier} — the {@link SignalNotifier} SPI
 *       implementation, unless the application supplies its own</li>
 * </ul>
 *
 * <p>Ordered after {@link MaestroAutoConfiguration}; the engine's
 * {@code @Nullable SignalNotifier} parameter receives the bean because bean
 * definitions are resolved before any bean is instantiated.
 *
 * @see PostgresSignalNotifier
 * @see PostgresWakeListener
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(name = "org.postgresql.PGConnection")
@ConditionalOnBean({WorkflowExecutor.class, DataSource.class})
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "maestro.signal.wake-notify", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PostgresSignalWakeAutoConfiguration {

    /**
     * This node's LISTEN session on the service's wake channel.
     *
     * @param maestroDataSource the {@link MaestroDataSource @MaestroDataSource} bean, if any
     * @param dataSource        the application's data source, used when there is
     *                          none; the dedicated connection is borrowed from the
     *                          one chosen
     * @param properties bound {@code maestro.*}; the channel derives from
     *                   {@code maestro.service-name}
     * @return the started listener; closed with the context
     */
    @Bean(destroyMethod = "close")
    // Also backs off when the application supplies its own SignalNotifier: the
    // listener would otherwise hold a pool connection nothing ever uses.
    @ConditionalOnMissingBean({PostgresWakeListener.class, SignalNotifier.class})
    public PostgresWakeListener maestroPostgresWakeListener(
            @MaestroDataSource ObjectProvider<DataSource> maestroDataSource,
            ObjectProvider<DataSource> dataSource, MaestroProperties properties) {
        var serviceName = Objects.requireNonNull(properties.getServiceName(),
                "maestro.service-name must be set (the engine checks this first)");
        var listener = new PostgresWakeListener(
                MaestroDataSources.resolve(maestroDataSource, dataSource), WakeChannel.channelFor(serviceName));
        listener.start();
        return listener;
    }

    /**
     * The Postgres {@link SignalNotifier}.
     *
     * @param maestroDataSource the {@link MaestroDataSource @MaestroDataSource} bean, if any
     * @param dataSource        the application's data source, used when there is
     *                          none; each {@code NOTIFY} borrows a connection from
     *                          the one chosen
     * @param listener          this node's listener
     * @return the notifier
     */
    @Bean
    @ConditionalOnMissingBean(SignalNotifier.class)
    public PostgresSignalNotifier postgresSignalNotifier(
            @MaestroDataSource ObjectProvider<DataSource> maestroDataSource,
            ObjectProvider<DataSource> dataSource, PostgresWakeListener listener) {
        return new PostgresSignalNotifier(
                MaestroDataSources.resolve(maestroDataSource, dataSource), listener, listener.channel());
    }
}
