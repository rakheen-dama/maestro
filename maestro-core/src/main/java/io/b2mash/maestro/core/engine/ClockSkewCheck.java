package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Compares this node's clock with the store's and logs a {@code WARN} when
 * they differ by more than {@link #WARN_ABOVE} (finding 3.7).
 *
 * <p>Due timers are polled on the store's clock
 * ({@link WorkflowStore#getDueTimers(int)}), but a {@code sleep()}'s
 * {@code fireAt} and an {@code awaitSignal()} deadline are still armed on the
 * clock of the node that schedules them, so a node whose clock is off arms its
 * deadlines off by the same amount. Every node's {@link TimerPoller} calls
 * {@link #maybeCheck()} at the end of each loop iteration, after its
 * leadership renewal and poll, leader or not.
 *
 * <h2>Cadence</h2>
 * <p>The first call checks; after that a call checks only once
 * {@link #INTERVAL} has passed since the previous check, measured on the
 * monotonic {@code nanoTime} source so a jumping wall clock cannot defeat the
 * cadence. One {@code storeTime()} read (Postgres: {@code SELECT now()}) per
 * node per interval.
 *
 * <h2>Measurement</h2>
 * <p>{@code before = clock.instant()}, {@code t = store.storeTime()},
 * {@code after = clock.instant()}. The skew is the distance of {@code t} from
 * the window {@code [before, after]} — see {@link #skew} — so the statement's
 * own latency can never produce a false {@code WARN}.
 *
 * <h2>Failures</h2>
 * <p>A store with no clock of its own ({@code storeTime()} empty) is logged
 * once at {@code DEBUG} and never asked again. Any {@link RuntimeException}
 * from the read — a {@code StoreUnavailableException} included — is logged at
 * {@code DEBUG} and retried at the next interval; it is never rethrown. This
 * runs on the poller thread, outside any workflow run, and records no outcome,
 * so a store fault here is not a workflow failure. An {@link Error} propagates
 * — the engine's control-flow rule.
 *
 * <p>The {@code WARN} carries the measured skew only: no workflow id, payload
 * or credentials.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #maybeCheck()} is {@code synchronized}; the poller calls it from
 * its single loop thread.
 */
final class ClockSkewCheck {

    private static final Logger logger = LoggerFactory.getLogger(ClockSkewCheck.class);

    /** A skew of more than this, either way, is warned. */
    static final Duration WARN_ABOVE = Duration.ofSeconds(1);

    /** How often a node checks, after its first check. */
    static final Duration INTERVAL = Duration.ofMinutes(10);

    private final WorkflowStore store;
    private final InstantSource clock;
    private final LongSupplier nanoTime;

    /** Latched once the store reports no clock of its own. */
    private volatile boolean noClock;

    private boolean checkedOnce;
    private long nextCheckNanos;

    /**
     * A check on the system clock and {@link System#nanoTime()}.
     *
     * @param store the store whose clock this node is compared with
     */
    ClockSkewCheck(WorkflowStore store) {
        this(store, InstantSource.system(), System::nanoTime);
    }

    /**
     * @param store    the store whose clock this node is compared with
     * @param clock    this node's wall clock
     * @param nanoTime the monotonic source the cadence is measured on
     */
    ClockSkewCheck(WorkflowStore store, InstantSource clock, LongSupplier nanoTime) {
        this.store = store;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /**
     * Checks this node's clock against the store's if it is time to, and logs a
     * {@code WARN} if they differ by more than {@link #WARN_ABOVE}.
     *
     * @return the measured skew (positive: this node is ahead); empty when this
     *         call did not check, the store has no clock, or the read failed.
     *         Never throws a {@link RuntimeException}.
     */
    synchronized Optional<Duration> maybeCheck() {
        if (noClock) {
            return Optional.empty();
        }
        var now = nanoTime.getAsLong();
        if (checkedOnce && now - nextCheckNanos < 0) {
            return Optional.empty();
        }
        checkedOnce = true;
        nextCheckNanos = now + INTERVAL.toNanos();
        try {
            var before = clock.instant();
            var storeTime = store.storeTime();
            var after = clock.instant();
            if (storeTime.isEmpty()) {
                noClock = true;
                logger.debug("The store has no clock of its own — the clock-skew check is off");
                return Optional.empty();
            }
            var skew = skew(before, storeTime.get(), after);
            warning(skew).ifPresent(logger::warn);
            return Optional.of(skew);
        } catch (RuntimeException e) {
            logger.debug("Clock-skew check could not read the store's clock — retried in {}: {}",
                    INTERVAL, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The distance of {@code storeTime} from the window {@code [before, after]}
     * this node read its clock in: zero inside it, positive when the store's
     * time is before the window (this node is ahead), negative when after it
     * (this node is behind).
     *
     * @param before    this node's clock just before the store was read
     * @param storeTime the store's clock
     * @param after     this node's clock just after the store was read
     * @return the skew; positive means this node is ahead
     */
    static Duration skew(Instant before, Instant storeTime, Instant after) {
        if (storeTime.isBefore(before)) {
            return Duration.between(storeTime, before);
        }
        if (storeTime.isAfter(after)) {
            return Duration.between(after, storeTime).negated();
        }
        return Duration.ZERO;
    }

    /**
     * The {@code WARN} for a skew, or empty when its magnitude is at most
     * {@link #WARN_ABOVE}.
     *
     * @param skew the measured skew; positive means this node is ahead
     * @return the warning text, or empty
     */
    static Optional<String> warning(Duration skew) {
        if (skew.abs().compareTo(WARN_ABOVE) > 0) {
            var ahead = !skew.isNegative();
            var millis = skew.abs().toMillis();
            // "can ... up to": an awaitSignal() deadline is also compared on the
            // clock of the node running the await, so a timeout can expire on
            // time; only the bound and its direction hold for both kinds.
            return Optional.of(("This node's clock is %d ms %s the database clock (warns above 1 s). Due timers "
                    + "are polled on the database clock, but sleep() and awaitSignal() deadlines are armed on "
                    + "the clock of the node that schedules them, so a deadline armed here is set %d ms %s "
                    + "than the database clock would set it: a sleep() can wake, and an awaitSignal() timeout "
                    + "can expire, up to %d ms %s. Check this node's time synchronisation (NTP).")
                    .formatted(millis, ahead ? "ahead of" : "behind", millis, ahead ? "later" : "earlier",
                            millis, ahead ? "late" : "early"));
        }
        return Optional.empty();
    }
}
