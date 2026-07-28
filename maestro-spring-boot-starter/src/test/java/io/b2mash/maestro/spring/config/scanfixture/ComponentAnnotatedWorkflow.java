package io.b2mash.maestro.spring.config.scanfixture;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.springframework.stereotype.Component;

/**
 * A workflow that IS already a Spring bean (annotated {@code @Component} /
 * registered by the user). The starter's classpath scan must skip it —
 * registering it twice would produce two beans of the same workflow type
 * and fail context startup with a duplicate-registration error.
 */
@Component
@DurableWorkflow(name = "component-registered")
public class ComponentAnnotatedWorkflow {

    @WorkflowMethod
    public String run(String input) {
        return input;
    }
}
