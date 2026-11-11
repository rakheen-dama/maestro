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
 * connections the host's request threads are waiting for. Annotating a second
 * {@code DataSource} bean gives the engine a pool of its own. Declare it with
 * {@code @Bean(defaultCandidate = false)} so it stays out of the host's way:
 * Spring Boot's own {@code DataSource} auto-configuration does not back off
 * because of it, and the host's unqualified injection points (JPA,
 * {@code JdbcTemplate}, its Flyway) never receive it.
 *
 * <pre>{@code
 * @Bean(defaultCandidate = false)
 * @MaestroDataSource
 * DataSource maestroDataSource() { ... }
 * }</pre>
 *
 * <p>Without {@code defaultCandidate = false}, the annotated bean is an
 * ordinary {@code DataSource} too: Boot's auto-configured one backs off and
 * every unqualified consumer gets Maestro's pool. Its {@code LISTEN} session
 * holds one connection from this pool for the application's lifetime.
 *
 * <p>Shaped like Spring Boot's {@code @FlywayDataSource}.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface MaestroDataSource {
}
