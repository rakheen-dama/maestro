package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.RunCancellation;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Everything one {@link WorkflowExecutor} has running that its shutdown must
 * stop and wait for: workflow runs, and — as later packages join them —
 * background loops, listener containers and one-shot tasks.
 *
 * <h2>Join before start, leave after release</h2>
 * <p>A member joins before it does any work and leaves as the very last thing
 * it does, once it has returned everything it holds (an instance lock, a
 * pooled connection, a consumer). A join is admitted only while the registry
 * is {@link State#OPEN}; a refused join means the caller must not start the
 * work at all. The registry never joins a thread: it waits for its member set
 * to empty, and every {@link Membership#leave()} wakes that wait.
 *
 * <h2>States</h2>
 * <ul>
 *   <li>{@link State#OPEN} — joins are admitted.</li>
 *   <li>{@link State#DRAINING} — set by {@link #close()} as shutdown begins.
 *       Every join is refused; members leave as they finish.</li>
 *   <li>{@link State#CLOSED} — set by {@link #terminate()} just before
 *       shutdown returns. Whatever is still registered then is reported as
 *       still running.</li>
 * </ul>
 *
 * <h2>Why this closes Issue 80</h2>
 * <p>{@link #close()} flips the state and copies the member set in one
 * critical section, and a join checks the state and adds the member in one
 * critical section. So every member is either in {@code close()}'s snapshot or
 * was refused; there is no weakly consistent iteration a late registration can
 * slip past.
 *
 * <h2>Public surface</h2>
 * <p>{@link #join}, {@link Membership#leave()}, {@link Member}, {@link Kind} and
 * {@link State} are public so modules outside {@code maestro-core} (the Kafka
 * containers, the Postgres loops) can join; like the {@code WorkflowStore} SPI
 * they are internal until 1.0 and may change without notice. Runs join through
 * the package-private {@link #joinRun}; the drain's methods are package-private
 * and take a {@link ShutdownDeadline}, never a {@code Duration}, so no wait has
 * a timeout of its own.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. One {@link ReentrantLock} guards the state and the member
 * set, with one {@link Condition} that every leave signals. No member code ever
 * runs under that lock: {@link Member#requestStop()} and
 * {@link RunCancellation} calls are made by the caller on a snapshot, outside
 * it.
 */
public final class LifecycleRegistry {

    /** The registry's state; it only ever moves forward. */
    public enum State {
        /** Joins are admitted. */
        OPEN,
        /** Shutdown has begun: every join is refused, members leave as they finish. */
        DRAINING,
        /** Shutdown has returned; whatever is still registered is reported as still running. */
        CLOSED
    }

    /** What kind of work a non-run member is; workflow runs join through {@link #joinRun} instead. */
    public enum Kind {
        /** A message consumer, such as a Kafka listener container. */
        CONSUMER,
        /** A background loop, such as a poller or a sweeper. */
        LOOP,
        /** A one-shot task, such as a startup check. */
        TASK
    }

    /**
     * What a non-run member does when shutdown asks it to stop.
     *
     * <p>{@link #requestStop()} must return at once, must never interrupt a
     * thread, must be idempotent, and may come before the member has started
     * or after it has ended.
     */
    public interface Member {

        /** @return a short name for log lines, e.g. {@code "retention-sweeper"} */
        String name();

        /** Asks the member to stop: returns at once, never interrupts, idempotent. */
        void requestStop();
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition left = lock.newCondition();
    /** Insertion-ordered, identity-keyed ({@link Membership} keeps {@code Object}'s equality). */
    private final Set<Membership> members = new LinkedHashSet<>();
    private State state = State.OPEN;
    private int runs;

    /** Creates an open, empty registry. */
    LifecycleRegistry() {
    }

    /**
     * Joins {@code member}, if the registry is still open.
     *
     * @param kind   what kind of work the member is
     * @param member what to ask to stop at shutdown
     * @return the membership, to {@link Membership#leave() leave} as the
     *         member's last act; empty when the registry is no longer
     *         {@link State#OPEN}, in which case the caller must not start the work
     */
    public Optional<Membership> join(Kind kind, Member member) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(member, "member");
        return Optional.ofNullable(admit(new Membership(kind, member.name(), member, null)));
    }

    /**
     * Joins one workflow run, before it takes its instance lock. The run's
     * {@link RunCancellation} is created by the caller at join time, so a stop
     * request reaches a run that has no thread yet.
     *
     * @param workflowId   the run's workflow id, for log lines
     * @param cancellation the run's stop latch
     * @return the membership, or {@code null} when the registry is no longer open
     */
    @Nullable Membership joinRun(String workflowId, RunCancellation cancellation) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(cancellation, "cancellation");
        return admit(new Membership(null, workflowId, null, cancellation));
    }

    private @Nullable Membership admit(Membership membership) {
        lock.lock();
        try {
            if (state != State.OPEN) {
                return null;
            }
            members.add(membership);
            if (membership.isRun()) {
                runs++;
            }
            return membership;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Moves {@link State#OPEN} to {@link State#DRAINING} and returns every
     * member registered at that instant, in join order. A second call returns
     * the members still registered and changes nothing.
     *
     * @return the snapshot; the caller asks each to stop, outside any lock
     */
    List<Membership> close() {
        lock.lock();
        try {
            if (state == State.OPEN) {
                state = State.DRAINING;
            }
            return List.copyOf(members);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until no workflow run is registered, or {@code deadline} passes.
     *
     * @param deadline the shutdown's deadline; the wait takes what is left of it
     * @return {@code true} if no run is left; {@code false} at the deadline or
     *         when the calling thread is interrupted (its flag is restored)
     */
    boolean awaitNoRuns(ShutdownDeadline deadline) {
        return awaitUntil(deadline, true);
    }

    /**
     * Waits until no member of any kind is registered, or {@code deadline} passes.
     *
     * @param deadline the shutdown's deadline; the wait takes what is left of it
     * @return {@code true} if nothing is left; {@code false} at the deadline or
     *         when the calling thread is interrupted (its flag is restored)
     */
    boolean awaitNoMembers(ShutdownDeadline deadline) {
        return awaitUntil(deadline, false);
    }

    private boolean awaitUntil(ShutdownDeadline deadline, boolean runsOnly) {
        lock.lock();
        try {
            while (runsOnly ? runs > 0 : !members.isEmpty()) {
                var nanos = deadline.remaining().toNanos();
                if (nanos <= 0) {
                    return false;
                }
                try {
                    left.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return every member still registered, in join order */
    List<Membership> remaining() {
        lock.lock();
        try {
            return List.copyOf(members);
        } finally {
            lock.unlock();
        }
    }

    /** Moves the registry to {@link State#CLOSED}; joins stay refused. */
    void terminate() {
        lock.lock();
        try {
            state = State.CLOSED;
        } finally {
            lock.unlock();
        }
    }

    /** @return the current state */
    State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    private void remove(Membership membership) {
        lock.lock();
        try {
            if (members.remove(membership)) {
                if (membership.isRun()) {
                    runs--;
                }
                left.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * One member's place in the registry.
     *
     * <p>Thread-safe: {@link #leave()} may be called from any thread, any
     * number of times.
     */
    public final class Membership {

        private final @Nullable Kind kind;
        private final String name;
        private final @Nullable Member member;
        private final @Nullable RunCancellation cancellation;

        private Membership(@Nullable Kind kind, String name, @Nullable Member member,
                           @Nullable RunCancellation cancellation) {
            this.kind = kind;
            this.name = name;
            this.member = member;
            this.cancellation = cancellation;
        }

        /**
         * Leaves the registry: the member's last act, once it has returned
         * everything it holds. Idempotent.
         */
        public void leave() {
            remove(this);
        }

        /** @return {@code true} for a workflow run */
        boolean isRun() {
            return cancellation != null;
        }

        /** @return the member's kind, or {@code null} for a workflow run */
        @Nullable Kind kind() {
            return kind;
        }

        /** @return the member's name; a run's workflow id */
        String name() {
            return name;
        }

        /**
         * Asks the member to stop, outside the registry's lock: a run's
         * cancellation is asked for {@code SHUTDOWN}, any other member's
         * {@link Member#requestStop()} is called.
         */
        void requestStop() {
            if (cancellation != null) {
                cancellation.requestShutdown();
            } else if (member != null) {
                member.requestStop();
            }
        }

        @Override
        public String toString() {
            return (kind == null ? "run" : kind.name().toLowerCase(java.util.Locale.ROOT)) + " '" + name + "'";
        }
    }

    /** @return the names of the runs among {@code snapshot}, in order */
    static List<String> runNames(List<Membership> snapshot) {
        var names = new ArrayList<String>();
        for (var membership : snapshot) {
            if (membership.isRun()) {
                names.add(membership.name());
            }
        }
        return names;
    }
}
