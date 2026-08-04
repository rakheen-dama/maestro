package io.b2mash.maestro.samples.loan.underwriting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Makes this service's Kafka sends observable, so a loan's journey across the
 * three services is <em>one</em> distributed trace rather than three unrelated
 * ones.
 *
 * <h2>Why this class has to exist</h2>
 * The loan sample's services talk to each other over their own domain topics
 * ({@code loans.verification.*}, {@code loans.underwriting.*}), published with
 * an injected {@code KafkaTemplate<String, byte[]>}. Maestro's
 * {@code KafkaTracePropagation} does not see those records — it injects W3C
 * {@code traceparent} only on the {@code maestro.tasks.*} /
 * {@code maestro.signals.*} topics the engine itself owns. So the header on a
 * domain record can only come from Spring Kafka's own producer instrumentation,
 * which {@link KafkaTemplate#setObservationEnabled(boolean)} turns on.
 *
 * <p>Spring Boot's {@code spring.kafka.template.observation-enabled} property
 * cannot reach it: that property configures Boot's auto-configured
 * {@code kafkaTemplate} bean, and Boot backs that bean off entirely
 * ({@code @ConditionalOnMissingBean(KafkaTemplate.class)}) because
 * {@code KafkaMessagingAutoConfiguration} already contributes
 * {@code maestroKafkaTemplate}. The only {@code KafkaTemplate} in the context is
 * therefore Maestro's, and setting the property has no effect at all — the
 * records go out with no headers and each service opens a fresh root trace.
 *
 * <p>Rather than introduce a second template and qualify every injection point,
 * this replaces Maestro's through the extension point it deliberately provides:
 * {@code maestroKafkaTemplate} is declared
 * {@code @ConditionalOnMissingBean(name = "maestroKafkaTemplate")}, so a bean of
 * that name defined here wins and the engine uses it too. Engine and domain
 * traffic then share one observed template.
 *
 * <p>The engine's manual {@code traceparent} injection still runs on its own
 * topics; the observation replaces that header with its own producer span, which
 * is a child of the same trace, so trace continuity is unchanged.
 *
 * <h2>Thread safety</h2>
 * {@link KafkaTemplate} is thread-safe and this class only configures one.
 */
@Configuration(proxyBeanMethods = false)
public class ObservedKafkaTemplateConfig {

    /**
     * Replaces {@code KafkaMessagingAutoConfiguration}'s template with an
     * observation-enabled one. The bean <b>name must stay
     * {@code maestroKafkaTemplate}</b> — that name is what makes the engine's
     * own definition back off.
     *
     * <p>The producer factory is Maestro's (same bootstrap servers, same
     * {@code String}/{@code byte[]} serializers, {@code acks=all}); only
     * observation is added. {@code KafkaTemplate} is {@code ApplicationContextAware}
     * and resolves the {@code ObservationRegistry} from the context itself, so no
     * registry needs to be passed in.
     *
     * @param maestroKafkaProducerFactory the engine's producer factory
     * @return an observation-enabled template shared by the engine and this service
     */
    @Bean
    public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
            ProducerFactory<String, byte[]> maestroKafkaProducerFactory
    ) {
        var template = new KafkaTemplate<>(maestroKafkaProducerFactory);
        template.setObservationEnabled(true);
        return template;
    }
}
