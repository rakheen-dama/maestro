package io.b2mash.maestro.store.postgres.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base class for the LISTEN/NOTIFY wake tests.
 *
 * <p>Provides a Postgres container shared by every suite in the JVM and a
 * started {@link PostgresWakeListener} on a per-test service channel. No
 * Flyway: {@code NOTIFY} needs no tables.
 *
 * <p>The container is started from a static initialiser rather than through
 * JUnit's {@code @Testcontainers}/{@code @Container} extension: that extension
 * stops a static container when its test <em>class</em> finishes, so an
 * inherited container would be recreated per subclass. Ryuk removes it at JVM
 * exit.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are per-test and confined to the test thread; the container is
 * guarded statically.
 */
abstract class PostgresNotifyTestSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_notify_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    protected PGSimpleDataSource dataSource;
    protected String serviceName;
    protected String channel;
    protected PostgresWakeListener listener;

    @BeforeEach
    void setUpNotify() {
        dataSource = newDataSource();
        serviceName = "notify-test-" + unique();
        channel = WakeChannel.channelFor(serviceName);
        listener = new PostgresWakeListener(dataSource, channel);
        listener.start();
    }

    @AfterEach
    void tearDownNotify() {
        if (listener != null) {
            listener.close();
        }
    }

    /** @return a new data source against the shared container */
    protected static PGSimpleDataSource newDataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /** @return a notifier over this test's listener and channel, publishing through {@code dataSource} */
    protected PostgresSignalNotifier newNotifier() {
        return new PostgresSignalNotifier(dataSource, listener, channel);
    }

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
