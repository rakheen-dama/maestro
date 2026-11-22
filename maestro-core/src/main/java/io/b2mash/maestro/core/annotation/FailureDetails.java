package io.b2mash.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts an exception class in to having its own properties recorded when an
 * activity fails with it, and rebuilt from that record on the first run and on
 * every replay alike (Issue 42).
 *
 * <p>Without this annotation an activity failure records only the exception's
 * class name and message. Workflow code always receives the exception
 * <em>rebuilt from the record</em> — never the object the activity threw — so
 * a field of an exception that has not opted in is not there on either path.
 *
 * <h2>What an author writes</h2>
 * <pre>{@code
 * @FailureDetails
 * public class PaymentDeclinedException extends RuntimeException {
 *     private final String reasonCode;
 *
 *     @JsonCreator
 *     public PaymentDeclinedException(@JsonProperty("message") String message,
 *                                     @JsonProperty("reasonCode") String reasonCode) {
 *         super(message);
 *         this.reasonCode = reasonCode;
 *     }
 *
 *     public String getReasonCode() { return reasonCode; }
 * }
 * }</pre>
 * <ul>
 *   <li><b>Recorded:</b> what the configured mapper writes for the exception
 *       (bean getters, {@code @JsonProperty} members), minus
 *       {@link Throwable}'s own properties ({@code message}, {@code cause},
 *       {@code stackTrace}, {@code suppressed}, {@code localizedMessage}). They
 *       are stored as JSON in the failure event: do not record secrets
 *       ({@code @JsonIgnore} leaves a property out).</li>
 *   <li><b>Rebuilt:</b> by the same mapper, from {@code {"message": …}} plus
 *       the recorded properties — give the class a {@code @JsonCreator}
 *       constructor (or static factory) taking {@code message} and each
 *       recorded property by name ({@code @JsonProperty}; without the
 *       annotations the mapper finds such a constructor only when the class
 *       is compiled with {@code -parameters}).</li>
 *   <li><b>Checked:</b> the rebuilt exception must be of the same class, carry
 *       the same message and write the same properties again. If it does not
 *       (or the class cannot be created, or its properties could not be
 *       written), workflow code gets a
 *       {@link io.b2mash.maestro.core.exception.ReconstructedActivityFailure}
 *       instead — on the first run and on replay alike — and the first run
 *       reports it ({@code EngineObserver.activityFailureUnrebuildable}, the
 *       {@code maestro.activity.failure.unrebuildable} counter and a
 *       {@code WARN}).</li>
 * </ul>
 * Whatever the class does,
 * {@link io.b2mash.maestro.core.exception.ActivityExecutionException#failure()}
 * exposes the recorded type, message and properties.
 *
 * <p>The annotation is per class: it is not inherited, so a subclass that
 * should record its properties carries it too. Whether a class can be rebuilt
 * is decided by trying, on the first failure (there is no check at startup: a
 * {@code throws} clause names a supertype of what is thrown, and the mapper
 * also finds creators by parameter name, so reflection alone cannot tell). Pin
 * it with a test that fails the activity once and asserts the cause's class.
 *
 * <p>Recording is the opt-in, not the rebuild: a failure record that carries
 * properties is rebuilt from them on every node that has the class, whether or
 * not that node's copy is still annotated.
 *
 * @see io.b2mash.maestro.core.exception.ActivityExecutionException#failure()
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FailureDetails {
}
