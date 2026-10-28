package io.b2mash.maestro.spring.config.invalidfixture.threadscope;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * A workflow bean in a custom, non-prototype scope (like {@code request} or
 * {@code session}): {@code getBean} may hand back the same object to several
 * runs, so the starter must refuse it just like a singleton.
 */
@Component
@Scope("thread")
@DurableWorkflow(name = "thread-scoped")
public class ThreadScopedWorkflow {

    /**
     * @param input the value to return
     * @return {@code input}
     */
    @WorkflowMethod
    public String run(String input) {
        return input;
    }
}
