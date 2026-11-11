package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.store.postgres.MaestroDataSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;

/**
 * Picks the {@link DataSource} every Maestro bean that talks to Postgres uses,
 * the way Spring Boot picks Flyway's: the {@link MaestroDataSource}-qualified
 * bean if there is one, otherwise the application's single (or
 * {@code @Primary}) {@code DataSource}.
 *
 * <p>Stateless; safe to call from any thread.
 */
final class MaestroDataSources {

    private MaestroDataSources() {
    }

    /**
     * @param qualified the {@code @MaestroDataSource}-qualified provider
     * @param any       the unqualified provider (default candidates only)
     * @return the {@code DataSource} Maestro uses
     * @throws IllegalStateException if more than one bean is qualified (and
     *                               none of them is {@code @Primary}), or if
     *                               there is no qualified bean and the
     *                               unqualified candidates have no single winner
     */
    static DataSource resolve(ObjectProvider<DataSource> qualified, ObjectProvider<DataSource> any) {
        DataSource maestro;
        try {
            maestro = qualified.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException e) {
            // Not chained: the message carries everything Spring's does, and a
            // chained cause would become the root cause a user reads first.
            throw new IllegalStateException("Maestro found " + e.getNumberOfBeansFound()
                    + " @MaestroDataSource beans; exactly one is allowed: " + e.getBeanNamesFound());
        }
        if (maestro != null) {
            return maestro;
        }
        var unique = any.getIfUnique();
        if (unique != null) {
            return unique;
        }
        throw new IllegalStateException("Maestro found " + any.stream().count()
                + " DataSource beans and none is @Primary or @MaestroDataSource. "
                + "Annotate the one Maestro should use with @MaestroDataSource.");
    }
}
