package io.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a query handler on a {@link DurableWorkflow}.
 *
 * <p>Query methods allow external callers to read the current state of a
 * running workflow without modifying it. Unlike {@link WorkflowMethod},
 * multiple {@code @QueryMethod} annotations are allowed per workflow class.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @DurableWorkflow
 * public class OrderFulfilmentWorkflow {
 *
 *     private volatile String currentStep = "init";
 *     private volatile int itemsProcessed = 0;
 *
 *     @WorkflowMethod
 *     public OrderResult fulfil(OrderInput input) {
 *         currentStep = "validating";
 *         // ... workflow logic
 *     }
 *
 *     @QueryMethod
 *     public String getCurrentStep() {
 *         return currentStep;
 *     }
 *
 *     @QueryMethod(name = "progress")
 *     public int getItemsProcessed() {
 *         return itemsProcessed;
 *     }
 * }
 * }</pre>
 *
 * <h2>Constraints</h2>
 * <ul>
 *   <li>The method must be {@code public} and non-static.</li>
 *   <li>The method must have a non-void return type.</li>
 *   <li>The method may accept zero or one parameter (the query input).</li>
 *   <li>Multiple {@code @QueryMethod} annotations per workflow class are allowed.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Query methods are invoked from the <b>caller's thread</b>, not the
 * workflow's virtual thread. The workflow author is responsible for ensuring
 * visibility of state fields read by query methods — use {@code volatile}
 * fields, {@code AtomicReference}, or explicit synchronization.
 *
 * <h2>Read-Only Contract</h2>
 * <p>Query methods must not modify workflow state or trigger side effects.
 * This is enforced by convention — the engine does not prevent writes, but
 * doing so will corrupt workflow determinism and break replay.
 *
 * @see DurableWorkflow
 * @see WorkflowMethod
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryMethod {

    /**
     * The query name used to invoke this handler.
     *
     * <p>Defaults to the method name if left empty.
     *
     * @return the query name
     */
    String name() default "";
}
