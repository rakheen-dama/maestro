package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.store.postgres.MaestroDataSource;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;
import java.util.ArrayList;

/**
 * Backs {@link ConditionalOnMaestroDataSource}.
 *
 * <p>Inspects bean definitions only — never instantiates a bean — the way
 * Spring Boot's {@code OnBeanCondition} does:
 * {@code getBeanNamesForType(DataSource.class, true, false)} for the
 * candidates, and {@code findAnnotationOnBean(name, MaestroDataSource.class,
 * false)} for the qualifier, which looks at the {@code @Bean} factory method,
 * the bean's class and its declared type — the same places Spring's qualifier
 * resolution accepts. Searches the context and its ancestors, as
 * {@code @ConditionalOnBean} does by default.
 *
 * <p>Stateless; safe to call from any thread.
 */
final class OnMaestroDataSourceCondition extends SpringBootCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var message = ConditionMessage.forCondition(ConditionalOnMaestroDataSource.class);
        var qualified = new ArrayList<String>();
        var defaultCandidates = new ArrayList<String>();
        for (BeanFactory factory = context.getBeanFactory();
             factory instanceof ConfigurableListableBeanFactory beanFactory;
             factory = beanFactory.getParentBeanFactory()) {
            for (var name : beanFactory.getBeanNamesForType(DataSource.class, true, false)) {
                if (ScopedProxyUtils.isScopedTarget(name)) {
                    continue;
                }
                if (beanFactory.findAnnotationOnBean(name, MaestroDataSource.class, false) != null) {
                    qualified.add(name);
                } else if (isDefaultCandidate(beanFactory, name)) {
                    defaultCandidates.add(name);
                }
            }
        }
        if (!qualified.isEmpty()) {
            return ConditionOutcome.match(message.found("@MaestroDataSource bean", "@MaestroDataSource beans")
                    .items(ConditionMessage.Style.QUOTE, qualified));
        }
        if (!defaultCandidates.isEmpty()) {
            return ConditionOutcome.match(message.found("DataSource bean", "DataSource beans")
                    .items(ConditionMessage.Style.QUOTE, defaultCandidates));
        }
        return ConditionOutcome.noMatch(message.didNotFind(
                "any DataSource bean that is a default candidate or qualified @MaestroDataSource").atAll());
    }

    private static boolean isDefaultCandidate(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!beanFactory.containsBeanDefinition(name)) {
            // A singleton registered directly, without a definition: always a candidate.
            return true;
        }
        var definition = beanFactory.getBeanDefinition(name);
        return definition.isAutowireCandidate()
                && (!(definition instanceof AbstractBeanDefinition abstractDefinition)
                        || abstractDefinition.isDefaultCandidate());
    }
}
