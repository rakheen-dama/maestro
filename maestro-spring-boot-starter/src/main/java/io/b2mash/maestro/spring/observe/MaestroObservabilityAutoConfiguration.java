package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Maestro's observability adapters (observability
 * design doc §7.2, amended per coordinator ruling — see below). Task 4 wired
 * the Micrometer meter adapter ({@code MetricsConfiguration}); Task 5 added the
 * tracing adapter ({@code TracingConfiguration}). Both share this outer class
 * and its {@code maestro.observability.*} property gate, and each is
 * independently switchable.
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
 *   <li>{@code TracingConfiguration} — Micrometer Tracing's {@link
 *       io.micrometer.tracing.Tracer} on the classpath, {@code
 *       maestro.observability.tracing.enabled} not {@code false} (default
 *       {@code true}), and a {@code Tracer} <em>and</em> {@code Propagator}
 *       bean actually present in the context.</li>
 * </ul>
 *
 * <h2>Ordering — {@code after}, not {@code before} (coordinator-approved
 * amendment to design §7.2)</h2>
 * The design doc's §7.2 paste-ready block originally declared this class
 * {@code @AutoConfiguration(before = MaestroAutoConfiguration.class)}. That
 * is incompatible with {@code MetricsConfiguration.maestroEngineGauges}'s
 * own {@code @ConditionalOnBean(WorkflowExecutor.class)}: Spring Boot
 * evaluates auto-configuration {@code @ConditionalOnBean} conditions in
 * {@code before}/{@code after} processing order, so a class ordered
 * {@code before} {@link MaestroAutoConfiguration} has its conditions
 * evaluated <em>before</em> {@code WorkflowExecutor}'s bean definition
 * exists — the gauges bean would never register, in every deployment,
 * unconditionally. Verified empirically (task report) and approved by the
 * coordinator, who also noted the shipped in-repo precedent: {@code
 * MaestroHealthAutoConfiguration} already orders itself {@code after
 * MaestroAutoConfiguration.class} for the identical reason (its indicator
 * bean method needs {@code WorkflowExecutor} to already exist).
 *
 * <h2>Ordering — {@code afterName} for Boot's own metrics auto-configuration
 * (fix round 1, F1)</h2>
 * {@code after = MaestroAutoConfiguration.class} alone still left {@code
 * @ConditionalOnBean(MeterRegistry.class)} evaluated <em>before</em> Boot
 * registers any {@code MeterRegistry} bean definition: Spring Boot's {@code
 * AutoConfigurationSorter} falls back to alphabetical order between classes
 * with no explicit relative ordering, and {@code
 * io.b2mash.maestro.spring.observe} sorts before {@code
 * org.springframework.boot.micrometer.metrics.autoconfigure} — so in a real
 * application (actuator + Micrometer on the classpath), this class's
 * conditions were evaluated first, every time, and the feature shipped
 * inert: no {@code MicrometerEngineObserver}, no gauges, zero {@code
 * maestro.*} meters, silently. This class now also declares {@code
 * afterName} for {@code
 * org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration}
 * and {@code
 * org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration}
 * — the exact ordering Boot's own {@code JvmMetricsAutoConfiguration} and
 * {@code SystemMetricsAutoConfiguration} use for their identical {@code
 * @ConditionalOnBean(MeterRegistry.class)} gate. {@code afterName} (string
 * class names), not {@code after} (class literals), because this module
 * depends on {@code micrometer-core} only as {@code compileOnly} — it does
 * not depend on {@code spring-boot-micrometer-metrics} at all, so a direct
 * class reference to {@code MetricsAutoConfiguration} would require adding
 * that as a further compile-time dependency; the string form needs no such
 * dependency and matches Boot's own precedent for optional peers.
 *
 * <h2>Ordering — the same gap again for tracing (Task 5)</h2>
 * {@code TracingConfiguration}'s {@code @ConditionalOnBean({Tracer.class,
 * Propagator.class})} has the identical exposure: {@code
 * io.b2mash.maestro.spring.observe} sorts before {@code
 * org.springframework.boot.micrometer.tracing.*} too, so without an explicit
 * {@code afterName} the tracing adapter would ship inert in every application
 * that gets its {@code Tracer} from Boot. The {@code afterName} list above
 * therefore also names Boot's tracing auto-configurations — the bridge-neutral
 * ones ({@code MicrometerTracingAutoConfiguration}, {@code
 * NoopTracerAutoConfiguration}) and both bridges ({@code
 * OpenTelemetryTracingAutoConfiguration}, {@code BraveAutoConfiguration}), so
 * the ordering holds whichever the application ships. Class names absent from
 * the classpath are ignored by {@code AutoConfigurationSorter}, which is why
 * naming all four is safe. Pinned by {@code
 * MaestroObservabilityAutoConfigurationTest.wiresThroughRealBootTracingAutoConfigurationChain},
 * which fails without these entries.
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
                // Tracing (Task 5) — the identical gap, for Tracer/Propagator.
                // Boot 4 splits the tracing auto-configurations across three
                // optional modules; naming all of them means the ordering holds
                // whichever bridge the application ships, and naming a class
                // that is absent from the classpath is simply ignored.
                "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
                "org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration",
                "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
                "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration"
        })
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

    /**
     * Span creation (design §3, Task 5). Gated on Micrometer Tracing being on
     * the classpath, on {@code maestro.observability.tracing.enabled} not being
     * {@code false}, and on Boot (or the application) having actually supplied
     * a {@code Tracer} <em>and</em> a {@code Propagator} bean — no tracer, no
     * spans, no property needed.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    @ConditionalOnProperty(prefix = "maestro.observability.tracing",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    static class TracingConfiguration {

        /**
         * @param tracer     the Micrometer tracer supplied by whichever bridge
         *                   the application ships
         * @param propagator the Micrometer propagator, used to restore a remote
         *                   parent from a signal's durable trace context
         * @return a {@link TracingEngineObserver}, discovered by {@link
         *         MaestroAutoConfiguration#maestroWorkflowExecutor} through its
         *         {@code ObjectProvider<EngineObserver>} and by {@code
         *         ActivityStubBeanPostProcessor} the same way
         */
        @Bean
        @ConditionalOnBean({Tracer.class, Propagator.class})
        TracingEngineObserver maestroTracingEngineObserver(Tracer tracer, Propagator propagator) {
            return new TracingEngineObserver(tracer, propagator);
        }
    }
}
