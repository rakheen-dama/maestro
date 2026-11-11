package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.store.postgres.MaestroDataSource;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Matches when there is a {@link javax.sql.DataSource} Maestro can use: any
 * {@code DataSource} bean that is a default candidate, or any bean qualified
 * {@link MaestroDataSource @MaestroDataSource} — including one declared
 * {@code @Bean(defaultCandidate = false)}, which Spring Boot's
 * {@code @ConditionalOnBean(DataSource.class)} does not see.
 *
 * <p>Evaluated against bean definitions, like {@code @ConditionalOnBean}, so
 * the annotated auto-configuration must be ordered after Spring Boot's
 * {@code DataSource} auto-configurations.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnMaestroDataSourceCondition.class)
@interface ConditionalOnMaestroDataSource {
}
