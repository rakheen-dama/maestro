package io.b2mash.maestro.messaging.kafka;

/**
 * The bean names, and so the documented qualifiers, of Maestro's three engine
 * Kafka beans.
 *
 * <p>The beans are <em>not default candidates</em>
 * ({@code @Bean(defaultCandidate = false)}): an injection point that names
 * only a type never receives one, so Spring Boot's own {@code kafkaTemplate},
 * {@code kafkaProducerFactory} and {@code kafkaConsumerFactory} register
 * beside them and serve the host's own Kafka traffic. Inject an engine bean
 * with its qualifier:
 *
 * <pre>{@code
 * public MyActivities(@Qualifier(MaestroKafkaBeans.TEMPLATE) KafkaTemplate<String, byte[]> template) { ... }
 * }</pre>
 *
 * <p>Without the qualifier a {@code KafkaTemplate<String, byte[]>} receives
 * Boot's {@code kafkaTemplate}. When that template cannot serialize
 * {@code byte[]} (Boot's default {@code StringSerializer}), startup fails
 * with an {@code IllegalStateException} that begins
 * {@code Bean '<bean>' injects a KafkaTemplate<…, byte[]> without a qualifier
 * (<injection point>) and received 'kafkaTemplate', whose value serializer
 * org.apache.kafka.common.serialization.StringSerializer cannot serialize
 * byte[]: every send through it would fail.} and names both fixes.
 *
 * <p>Replace one by declaring a bean with the same name, with
 * {@code defaultCandidate = false}:
 * <ul>
 *   <li>{@link #PRODUCER_FACTORY}: {@code defaultCandidate = false} is
 *       <b>required</b>. A plain {@code @Bean} of that name makes Boot's
 *       {@code kafkaProducerFactory} back off while Boot's
 *       {@code kafkaTemplate} still registers, and startup fails with
 *       {@code NoSuchBeanDefinitionException: No qualifying bean of type
 *       'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'}.
 *       A Spring Boot application reports it as
 *       {@code The bean 'maestroKafkaProducerFactory' replaces Maestro's engine
 *       producer factory but is a default candidate, so Spring Boot's
 *       kafkaProducerFactory backed off.}, with the action: declare it
 *       {@code @Bean(name = MaestroKafkaBeans.PRODUCER_FACTORY, defaultCandidate = false)},
 *       or define your own {@code KafkaTemplate}.</li>
 *   <li>{@link #TEMPLATE} and {@link #CONSUMER_FACTORY}: recommended. A plain
 *       {@code @Bean} of that name also replaces the engine's, but Boot's
 *       {@code kafkaTemplate} (respectively {@code kafkaConsumerFactory}) then
 *       backs off and the host's unqualified injection points of that type
 *       receive the replacement.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> constants only.
 */
public final class MaestroKafkaBeans {

    /** Maestro's {@code KafkaTemplate<String, byte[]>}, which publishes engine signals and lifecycle events. */
    public static final String TEMPLATE = "maestroKafkaTemplate";

    /** Maestro's {@code ProducerFactory<String, byte[]>}, behind {@link #TEMPLATE}. */
    public static final String PRODUCER_FACTORY = "maestroKafkaProducerFactory";

    /** Maestro's {@code ConsumerFactory<String, byte[]>}, behind every engine and {@code @MaestroSignalListener} consumer. */
    public static final String CONSUMER_FACTORY = "maestroKafkaConsumerFactory";

    private MaestroKafkaBeans() {
    }
}
