package io.b2mash.maestro.store.postgres;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifier for the {@link javax.sql.DataSource} Maestro uses — its store,
 * schema migrations, distributed lock, lock cleaner and {@code LISTEN/NOTIFY}
 * signal wake — instead of the application's own.
 *
 * <p>Without it, Maestro uses the application's single (or {@code @Primary})
 * {@code DataSource}, sharing its connection pool: a busy engine can then hold
 * connections the host's request threads are waiting for. Annotating a
 * {@code DataSource} bean gives the engine a pool of its own. The wake
 * listener holds one connection from that pool for the application's
 * lifetime, and every other Maestro component borrows from it.
 *
 * <p><b>The application also has its own {@code DataSource}</b> (Spring Boot's
 * auto-configured one, or its own {@code @Primary}): declare Maestro's with
 * {@code defaultCandidate = false}. That keeps it out of the host's way —
 * Boot's {@code DataSource} auto-configuration does not back off because of
 * it, and the host's unqualified injection points (JPA, {@code JdbcTemplate},
 * its Flyway) never receive it:
 *
 * <pre>{@code
 * @Bean(defaultCandidate = false)
 * @MaestroDataSource
 * DataSource maestroDataSource() { ... }
 * }</pre>
 *
 * <p><b>Maestro's pool is the only one</b>: a plain bean is simplest. Boot's
 * {@code DataSource} auto-configuration backs off, and any unqualified
 * consumer gets this pool too:
 *
 * <pre>{@code
 * @Bean
 * @MaestroDataSource
 * DataSource maestroDataSource() { ... }
 * }</pre>
 *
 * <p>A {@code defaultCandidate = false} bean as the only {@code DataSource}
 * also works for Maestro, but Boot's {@code DataSource} auto-configuration
 * does not see it: unless that auto-configuration is excluded, Boot still
 * builds a host pool from {@code spring.datasource.*} and, with no URL, fails
 * startup ("Failed to determine a suitable driver class").
 *
 * <p>Exactly one bean may carry this qualifier. The qualifier may sit on the
 * {@code @Bean} method or on the bean's class. Shaped like Spring Boot's
 * {@code @FlywayDataSource}.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface MaestroDataSource {
}
