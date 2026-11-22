package io.b2mash.maestro.core.exception;

import java.util.List;

/**
 * Thrown when an activity proxy is created for an interface with a method
 * declared to return a {@link java.util.concurrent.Future} or a
 * {@link java.util.concurrent.CompletionStage} (or any subtype, such as
 * {@link java.util.concurrent.CompletableFuture}) — Issue 49.
 *
 * <p>An activity call is synchronous and its result is recorded as JSON: for
 * a future that is its bean properties ({@code done}, {@code cancelled}, …),
 * never its value. A replay after a restart reads that back as a new
 * {@code CompletableFuture} nothing ever completes — {@code join()} blocks the
 * workflow thread for good — or, for {@code Future} and
 * {@code CompletionStage}, cannot read it back at all and stands every
 * recovery down. Such an interface is therefore refused when its proxy is
 * built: at context start under the Spring starter, and at
 * {@code startWorkflow} under the test kit, before anything is persisted.
 *
 * <p>Return the value itself ({@code String}, not
 * {@code CompletableFuture<String>}); run concurrent work with
 * {@code WorkflowContext.parallel(...)}. A future as a <em>parameter</em> is
 * not refused, and neither is a {@code static} interface method (a proxy
 * never dispatches one).
 */
public final class UnsupportedActivityReturnTypeException extends MaestroException {

    private final String activityInterface;
    private final List<String> methods;

    /**
     * @param activityInterface the activity interface's binary name
     * @param methods           the names of its offending methods, in name order
     * @param signatures        each offending method rendered as
     *                          {@code name(ParamTypes) returns genericReturnType}, in the same order
     */
    public UnsupportedActivityReturnTypeException(String activityInterface, List<String> methods,
                                                  List<String> signatures) {
        super(("Activity interface %s declares %s returning a Future or CompletionStage: %s. An activity call "
                + "is synchronous and its result is recorded as JSON — a future's bean properties, never its "
                + "value — so after a restart it reads back as a future nothing completes (CompletableFuture) "
                + "or cannot be read back at all (Future, CompletionStage). Return the value itself (e.g. String "
                + "rather than CompletableFuture<String>) and run concurrent work with WorkflowContext.parallel(...).")
                .formatted(activityInterface, methods.size() == 1 ? "a method" : methods.size() + " methods",
                        String.join(", ", signatures)));
        this.activityInterface = activityInterface;
        this.methods = List.copyOf(methods);
    }

    /** Returns the activity interface's binary name. */
    public String activityInterface() {
        return activityInterface;
    }

    /** Returns the names of the methods declared to return a future, in name order. */
    public List<String> methods() {
        return methods;
    }
}
