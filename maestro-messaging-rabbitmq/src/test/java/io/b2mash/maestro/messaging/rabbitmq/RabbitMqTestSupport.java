package io.b2mash.maestro.messaging.rabbitmq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Base class for {@code maestro-messaging-rabbitmq} integration tests.
 *
 * <p>Provides a RabbitMQ broker shared by every suite in the JVM and a
 * ready-to-use {@link RabbitMqWorkflowMessaging} per test.
 *
 * <p>The container is started from a static initialiser rather than through
 * JUnit's {@code @Testcontainers}/{@code @Container} extension — mirroring
 * {@code PostgresMessagingTestSupport} in {@code maestro-messaging-postgres}.
 * That extension stops a static container when its test <em>class</em>
 * finishes, so if this base class ever gains a second subclass, the shared
 * container declared in the parent would be recreated (and torn down again)
 * per subclass rather than once for the JVM. Starting it eagerly here avoids
 * the pitfall regardless of how many suites end up extending this class.
 * Ryuk removes it at JVM exit.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are per-test and confined to the test thread; the container is
 * guarded statically.
 */
abstract class RabbitMqTestSupport {

    @SuppressWarnings("resource")
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management");

    static {
        rabbitmq.start();
    }

    protected CachingConnectionFactory connectionFactory;
    protected RabbitTemplate rabbitTemplate;
    protected RabbitAdmin admin;
    protected ObjectMapper objectMapper;
    protected RabbitMqWorkflowMessaging messaging;

    /** Extra instances created via {@link #newMessaging(RabbitMqRedeliveryConfig)}, closed in {@link #tearDownMessaging()}. */
    private final List<RabbitMqWorkflowMessaging> extraMessagingInstances = new CopyOnWriteArrayList<>();

    /** The connection factories backing {@link #extraMessagingInstances}, closed alongside them. */
    private final List<CachingConnectionFactory> extraConnectionFactories = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUpMessaging() {
        objectMapper = JsonMapper.builder().build();
        connectionFactory = newConnectionFactory();
        rabbitTemplate = new RabbitTemplate(connectionFactory);
        admin = new RabbitAdmin(connectionFactory);
        messaging = new RabbitMqWorkflowMessaging(rabbitTemplate, connectionFactory, objectMapper);
    }

    @AfterEach
    void tearDownMessaging() {
        if (messaging != null) {
            messaging.destroy();
        }
        for (var extra : extraMessagingInstances) {
            extra.destroy();
        }
        extraMessagingInstances.clear();
        for (var extraCf : extraConnectionFactories) {
            extraCf.destroy();
        }
        extraConnectionFactories.clear();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** @return a new connection factory against the shared container */
    protected static CachingConnectionFactory newConnectionFactory() {
        var cf = new CachingConnectionFactory(rabbitmq.getHost(), rabbitmq.getAmqpPort());
        cf.setUsername(rabbitmq.getAdminUsername());
        cf.setPassword(rabbitmq.getAdminPassword());
        return cf;
    }

    /**
     * Builds an independent messaging instance with a bespoke redelivery
     * policy, so a test can exhaust the attempt budget in milliseconds
     * instead of the production default's minutes. Cleaned up automatically.
     *
     * @param redelivery the redelivery policy
     * @return a new instance
     */
    protected RabbitMqWorkflowMessaging newMessaging(RabbitMqRedeliveryConfig redelivery) {
        var cf = newConnectionFactory();
        var instance = new RabbitMqWorkflowMessaging(new RabbitTemplate(cf), cf, objectMapper, redelivery);
        extraMessagingInstances.add(instance);
        extraConnectionFactories.add(cf);
        return instance;
    }

    /** @return a short unique token, for building collision-free queue/service names */
    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
