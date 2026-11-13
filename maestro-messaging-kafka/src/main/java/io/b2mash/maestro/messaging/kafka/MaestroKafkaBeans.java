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
 * <p>Replace one by declaring a bean with the same name, with
 * {@code defaultCandidate = false} so Boot's bean of that type still
 * registers. A plain {@code @Bean} of that name also replaces it, but then
 * Boot's bean of the same type backs off and the host's unqualified injection
 * points receive the replacement.
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
