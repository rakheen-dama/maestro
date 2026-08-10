package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the audit F8 fix: {@code maestro.enabled=false} is documented as the
 * master kill-switch, but before this fix {@link MaestroHealthAutoConfiguration}
 * had no direct gate on it at all — it activated purely off
 * {@code @ConditionalOnBean(WorkflowExecutor.class)}. In a real application
 * that's transitively correct (no {@code WorkflowExecutor} bean exists once
 * {@code MaestroAutoConfiguration} itself backs off), but it means the health
 * indicator's own activation is not actually governed by the documented
 * flag — anything else that supplies a {@code WorkflowExecutor} bean (a test
 * fixture, a future refactor, a user override) would light this indicator
 * back up even with {@code maestro.enabled=false}.
 *
 * <p>This test proves the direct gate by supplying a {@link WorkflowExecutor}
 * bean by hand — bypassing {@code MaestroAutoConfiguration} entirely — so the
 * only thing standing between {@code maestro.enabled=false} and a live
 * indicator bean is this class's own condition (RED shape: beans present).
 */
@DisplayName("MaestroHealthAutoConfiguration — maestro.enabled=false (audit F8)")
class MaestroHealthAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module even when a WorkflowExecutor bean is independently present")
    void maestroDisabledMeansNoIndicatorEvenWithExecutorBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MaestroHealthAutoConfiguration.class))
                .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
                .withPropertyValues("maestro.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(MaestroHealthIndicator.class);
                });
    }
}
