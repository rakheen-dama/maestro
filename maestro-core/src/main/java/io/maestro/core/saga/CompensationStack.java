package io.maestro.core.saga;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe LIFO stack of compensation entries registered during workflow execution.
 *
 * <p>Compensations are pushed after each successfully completed activity
 * (either via {@code @Compensate} annotation or manual
 * {@code workflow.addCompensation(Runnable)}). On workflow failure,
 * the {@link io.maestro.core.saga.SagaManager} calls {@link #unwind()}
 * to retrieve all entries in reverse order for execution.
 *
 * <h2>Thread Safety</h2>
 * <p>Backed by a {@link ConcurrentLinkedDeque}, safe for concurrent pushes
 * from parallel workflow branches. The {@link #unwind()} method drains the
 * stack into an unmodifiable list.
 */
public final class CompensationStack {

    private final ConcurrentLinkedDeque<CompensationEntry> stack = new ConcurrentLinkedDeque<>();
    private final AtomicInteger manualCounter = new AtomicInteger(0);

    /**
     * Pushes a named compensation onto the stack.
     *
     * <p>Used by the {@code @Compensate} integration in the activity proxy,
     * which provides the activity name as the step name.
     *
     * @param stepName     the compensation step name for logging and events
     * @param compensation the executable compensation action
     */
    public void push(@NonNull String stepName, @NonNull Runnable compensation) {
        stack.push(new CompensationEntry(stepName, compensation));
    }

    /**
     * Pushes a compensation onto the stack with an auto-generated step name.
     *
     * <p>Used for manual compensations registered via
     * {@code workflow.addCompensation(Runnable)}.
     *
     * @param compensation the executable compensation action
     */
    public void push(@NonNull Runnable compensation) {
        var index = manualCounter.getAndIncrement();
        stack.push(new CompensationEntry("$compensation:" + index, compensation));
    }

    /**
     * Drains the stack and returns all entries in LIFO order (most recent first).
     *
     * <p>After this call, the stack is empty. The returned list is unmodifiable.
     *
     * @return the compensation entries in reverse registration order
     */
    public @NonNull List<@NonNull CompensationEntry> unwind() {
        var entries = new ArrayList<CompensationEntry>();
        CompensationEntry entry;
        while ((entry = stack.poll()) != null) {
            entries.add(entry);
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns whether the stack has no compensations registered.
     *
     * @return {@code true} if the stack is empty
     */
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    /**
     * Returns the number of compensations currently registered.
     *
     * @return the stack size
     */
    public int size() {
        return stack.size();
    }
}
