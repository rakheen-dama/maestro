package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Maestro's observability adapters (observability
 * design doc §7.2). Task 4 (this class) wires the Micrometer meter adapter;
 * a later task adds a sibling {@code TracingConfiguration} nested class for
 * span creation, sharing this same outer class and its {@code
 * maestro.observability.*} property gate.
 *
 * <p>Activates when {@code maestro.enabled} is {@code true} (default,
 * matching {@link MaestroAutoConfiguration}'s own gate). Each nested
 * configuration then gates itself independently:
 * <ul>
 *   <li>{@code MetricsConfiguration} — {@code Micrometer}'s
 *       {@link MeterRegistry} on the classpath, {@code
 *       maestro.observability.metrics.enabled} not {@code false} (default
 *       {@code true}), and a {@code MeterRegistry} bean actually present in
 *       the context.</li>
 * </ul>
 *
 * <h2>Ordering — deviation from the design doc, recorded for a coordinator
 * ruling</h2>
 * The design doc's §7.2 paste-ready block declares this class {@code
 * @AutoConfiguration(before = MaestroAutoConfiguration.class)}. That is
 * incompatible with {@code MetricsConfiguration.maestroEngineGauges}'s own
 * {@code @ConditionalOnBean(WorkflowExecutor.class)}: Spring Boot evaluates
 * auto-configuration {@code @ConditionalOnBean} conditions in {@code
 * before}/{@code after} processing order, so a class ordered {@code before}
 * {@link MaestroAutoConfiguration} has its conditions evaluated <em>before</em>
 * {@code WorkflowExecutor}'s bean definition exists — the gauges bean would
 * never register, in every deployment, unconditionally. This was verified
 * empirically (see the task report) with a minimal two-auto-configuration
 * reproduction: a nested {@code @ConditionalOnBean(name = "aBean")} bean
 * registered {@code false} when its outer class was {@code before} the
 * class defining {@code aBean}, and {@code true} when {@code after}.
 * <p>
 * This class is therefore ordered {@code after = MaestroAutoConfiguration.class}
 * instead. The design's own rationale for {@code before} — visibility of the
 * {@code MicrometerEngineObserver} bean to {@code
 * MaestroAutoConfiguration.maestroWorkflowExecutor}'s {@code
 * ObjectProvider<EngineObserver>} — is unaffected by this change: {@code
 * ObjectProvider} resolution happens lazily at actual bean instantiation,
 * which occurs only after every auto-configuration class's bean
 * <em>definitions</em> (from every class, regardless of processing order)
 * have already been registered. The design doc itself calls the {@code
 * before} ordering "belt-and-braces, not load-bearing" for exactly this
 * reason.
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MaestroObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "maestro.observability.metrics",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MetricsConfiguration {

        /**
         * @param registry the Micrometer registry to bind {@code EngineObserver}
         *                 callbacks to
         * @return a {@link MicrometerEngineObserver}, discovered by {@link
         *         MaestroAutoConfiguration#maestroWorkflowExecutor} through its
         *         {@code ObjectProvider<EngineObserver>} and by {@code
         *         ActivityStubBeanPostProcessor} the same way
         */
        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        MicrometerEngineObserver maestroMicrometerEngineObserver(MeterRegistry registry) {
            return new MicrometerEngineObserver(registry);
        }

        /**
         * @param registry the Micrometer registry the gauges are registered against
         * @param executor the executor the gauges read from
         * @return a {@link MaestroEngineGauges} holder — its constructor performs
         *         the actual registration
         */
        @Bean
        @ConditionalOnBean({MeterRegistry.class, WorkflowExecutor.class})
        MaestroEngineGauges maestroEngineGauges(MeterRegistry registry, WorkflowExecutor executor) {
            return new MaestroEngineGauges(registry, executor);
        }
    }
}
