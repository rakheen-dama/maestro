package io.b2mash.maestro.spring.config.scanfixture.singleton;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.springframework.stereotype.Component;

/**
 * A workflow the user registered as an ordinary — singleton — Spring bean
 * ({@code @Component} with no {@code @Scope}). One object shared by every run
 * would leak instance fields between concurrent workflows, so the starter
 * must refuse to start with it.
 */
@Component
@DurableWorkflow(name = "singleton-component")
public class SingletonComponentWorkflow {

    /**
     * @param input the value to return
     * @return {@code input}
     */
    @WorkflowMethod
    public String run(String input) {
        return input;
    }
}
