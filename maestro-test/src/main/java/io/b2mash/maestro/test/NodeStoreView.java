package io.b2mash.maestro.test;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One simulated node's view of the shared {@link InMemoryWorkflowStore}: the
 * seam {@link TestWorkflowEnvironment} uses to crash a node.
 *
 * <p>A {@link Proxy} implementing {@link WorkflowStore}, dispatching every
 * call to the delegate with {@link Method#invoke}. Dispatching reflectively —
 * rather than a hand-written decorator — matters for two reasons: the fenced
 * {@code RunLease} overloads the engine uses for every write from a workflow
 * thread are {@code default} methods on the SPI, and a decorator that forgot
 * to override one would route it to the <em>unfenced</em> default instead of
 * the in-memory store's real fenced override; and a method added to the SPI
 * later needs no upkeep here (only a read/write classification, which
 * {@code NodeStoreViewTest} forces).
 *
 * <h2>The crash</h2>
 * <p>Writes that persist an event — {@code appendEvent} (both forms),
 * {@code appendEventReturningPayload} (the engine's live activity outcome and
 * side-effect appends), {@code appendEventAndConsumeSignal} (when it returns
 * {@code true}) and
 * {@code completeInstance} (when it returns {@code true}) — are offered to
 * the {@link CrashPolicy} before and after the delegate call. A policy that
 * answers with a reason kills the node and throws
 * {@code ExecutorShutdownException("Simulated crash: <reason>")}: an existing
 * engine control-flow {@code Error}, so a workflow's own
 * {@code catch (Exception)} cannot swallow it, saga and {@code parallel()}
 * rethrow it, and the executor treats it exactly as a node stopping under the
 * workflow — it writes nothing, leaves the instance recoverable and releases
 * the instance lock.
 *
 * <p>Once dead, every <em>write</em> (any method not named {@code get*} or
 * {@code find*}) throws the same error — but only on a thread bound to a
 * {@link WorkflowContext}, i.e. one of this node's workflow, branch,
 * compensation or activity-attempt threads. The test thread still reaches
 * the store, which is how a signal or a timer "lands while the service is
 * down". Reads always pass: a dead node's parked threads may still look, they
 * just cannot record anything.
 *
 * <p><b>Thread safety:</b> thread-safe. A workflow thread's write holds the
 * read side of a read/write gate across its dead check, the policy and the
 * delegate call; {@link #kill} takes the write side. So when a crash fires,
 * every write already in flight on the node finishes first, and
 * {@link CrashPolicy#crashed} — where the crash is announced — runs only once
 * no further write can land.
 */
final class NodeStoreView implements InvocationHandler {

    /** Decides whether persisting an event crashes the node. */
    interface CrashPolicy {

        /** Never crashes. */
        CrashPolicy NONE = new CrashPolicy() {
            @Override
            public @Nullable String before(WorkflowEvent event) {
                return null;
            }

            @Override
            public @Nullable String after(WorkflowEvent event) {
                return null;
            }
        };

        /**
         * Consulted on a workflow thread just before {@code event} is written.
         *
         * @param event the event about to be persisted
         * @return why the node crashes now (the event is lost), or {@code null}
         *         to let the write proceed
         */
        @Nullable String before(WorkflowEvent event);

        /**
         * Consulted on a workflow thread once {@code event} is durably written.
         *
         * @param event the event just persisted
         * @return why the node crashes now (the event stays persisted), or
         *         {@code null} to carry on
         */
        @Nullable String after(WorkflowEvent event);

        /**
         * Called once the node is dead because {@link #before} or {@link #after}
         * answered with {@code reason} — on the crashing thread, after every
         * write that was in flight on this node has finished, so nothing this
         * node writes can land after this call. The place to announce the crash.
         *
         * @param reason the reason the policy gave
         */
        default void crashed(String reason) {
        }
    }

    private final WorkflowStore delegate;
    private final CrashPolicy policy;
    private final WorkflowStore proxy;
    private volatile @Nullable String deathReason;

    // Workflow-thread writes hold the read side across their dead check and the
    // delegate call; kill() takes the write side. So once kill() returns, no
    // write this node started can still land — "dead" is a clean cut.
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

    private NodeStoreView(WorkflowStore delegate, CrashPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
        this.proxy = (WorkflowStore) Proxy.newProxyInstance(
                WorkflowStore.class.getClassLoader(), new Class<?>[]{WorkflowStore.class}, this);
    }

    /**
     * @param delegate the shared store every node of the environment writes to
     * @param policy   the crash plan to consult on each persisted event
     * @return a live view
     */
    static NodeStoreView over(WorkflowStore delegate, CrashPolicy policy) {
        return new NodeStoreView(delegate, policy);
    }

    /** @return the {@link WorkflowStore} this node's executor and activity proxies use */
    WorkflowStore store() {
        return proxy;
    }

    /**
     * Kills the node: from now on its workflow threads cannot write. Waits for
     * the writes already in flight on this node to finish first, so none lands
     * after this returns. Idempotent; the first reason is kept. Must not be
     * called from inside a write through this view.
     *
     * @param reason why, for the error the next blocked write throws
     */
    void kill(String reason) {
        gate.writeLock().lock();
        try {
            if (deathReason == null) {
                deathReason = reason;
            }
        } finally {
            gate.writeLock().unlock();
        }
    }

    /** @return whether the node is dead */
    boolean isDead() {
        return deathReason != null;
    }

    /**
     * Whether a {@link WorkflowStore} method changes state. Everything except the
     * {@code get*}/{@code find*} reads does.
     *
     * @param method a {@link WorkflowStore} method
     * @return {@code true} for a write
     */
    static boolean isWrite(Method method) {
        var name = method.getName();
        return !(name.startsWith("get") || name.startsWith("find"));
    }

    @Override
    public @Nullable Object invoke(Object self, Method method, @Nullable Object @Nullable [] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(self, method, args);
        }
        if (!WorkflowContext.scopedValue().isBound() || !isWrite(method)) {
            // Reads, and the test thread's writes ("a signal lands while the
            // service is down"), always reach the store.
            return dispatch(method, args);
        }
        String reason;
        gate.readLock().lock();
        try {
            if (isDead()) {
                throw crash("node is down (" + deathReason + ") — " + method.getName() + " refused");
            }
            var event = persistedEvent(method, args);
            reason = event == null ? null : policy.before(event);
            if (reason == null) {
                var result = dispatch(method, args);
                reason = event == null || Boolean.FALSE.equals(result) ? null : policy.after(event);
                if (reason == null) {
                    return result;
                }
            }
        } finally {
            gate.readLock().unlock();
        }
        // Outside the read side: kill() waits for every other in-flight write.
        kill(reason);
        policy.crashed(reason);
        throw crash(reason);
    }

    /**
     * The event a write persists, if it is one the crash policy is offered:
     * {@code appendEvent(event[, lease])}, {@code appendEventReturningPayload(event, lease)},
     * {@code appendEventAndConsumeSignal(memo, lease, id)}
     * and {@code completeInstance(terminal, event[, lease])}. A {@code false}
     * return from the latter two means nothing was written, so no "after".
     */
    private static @Nullable WorkflowEvent persistedEvent(Method method, @Nullable Object @Nullable [] args) {
        if (args == null) {
            return null;
        }
        return switch (method.getName()) {
            case "appendEvent", "appendEventReturningPayload", "appendEventAndConsumeSignal" ->
                    args[0] instanceof WorkflowEvent e ? e : null;
            case "completeInstance" -> args.length > 1 && args[1] instanceof WorkflowEvent e ? e : null;
            default -> null;
        };
    }

    private @Nullable Object dispatch(Method method, @Nullable Object @Nullable [] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static ExecutorShutdownException crash(String reason) {
        return new ExecutorShutdownException("Simulated crash: " + reason);
    }

    private @Nullable Object objectMethod(Object self, Method method, @Nullable Object @Nullable [] args) {
        return switch (method.getName()) {
            case "equals" -> args != null && self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "NodeStoreView[" + (isDead() ? "dead: " + deathReason : "live") + "]";
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }
}
