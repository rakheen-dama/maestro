package io.b2mash.maestro.integration.upgrade.cbf5a2a;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.TimerCancelledException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The frozen fixture that produced {@code upgrade/cbf5a2a/seeded-history.sql}:
 * the workflows, activity interfaces, payload records, the one activity
 * implementation, and the {@link Seeds} catalogue of every seeded workflow.
 *
 * <h2>Frozen</h2>
 * <p>{@code SeededHistoryCapture} ran these classes on the build at
 * {@code cbf5a2a} (through {@code scripts/capture-upgrade-fixture.sh history
 * cbf5a2a cbf5a2a}) and dumped the history they wrote. That history is only
 * meaningful against exactly this source: the step order decides each
 * workflow's sequence numbers and event types, and the replay half of the
 * upgrade test re-runs these very classes over it. {@code capture.properties}
 * therefore pins the SHA-256 of this file's bytes, and
 * {@code SeededHistoryUpgradeIT.theDumpWasCapturedAtCbf5a2a} fails on any
 * byte change — comments and whitespace included. Never edit this file; a
 * later release adds a new fixture in a new package (RELEASING.md § Upgrade
 * fixtures).
 *
 * <p>It uses only the public workflow-author API, so it compiles unchanged on
 * the build at {@code cbf5a2a} and on every later build. Activity results
 * depend only on their arguments, so a replay can compare them with what
 * {@code cbf5a2a} recorded.
 */
public final class SeededHistoryWorkflows {

    private SeededHistoryWorkflows() {
    }

    // ── Seeded catalogue ──────────────────────────────────────────────────

    /**
     * The catalogue of seeded workflows: the contract WP1 and WP3 build on.
     *
     * <table>
     *   <caption>Seeded workflows (ids are {@code workflowId}s)</caption>
     *   <tr><th>Id</th><th>Service</th><th>Workflow and input</th><th>Status at capture</th><th>For</th></tr>
     *   <tr><td>{@code seed-completed}</td><td>{@code upgrade-fixture}</td>
     *       <td>Order: nap 1 s, express 1 s, cancellable 1 day, cancelled at capture</td>
     *       <td>COMPLETED</td><td>every Order event type in a terminal run</td></tr>
     *   <tr><td>{@code seed-parked-timer}</td><td>{@code upgrade-fixture}</td>
     *       <td>Order: nap 10 min, express 1 s, cancellable 1 ms</td>
     *       <td>WAITING_TIMER (step 7)</td><td>the timer fires on this build</td></tr>
     *   <tr><td>{@code seed-parked-express}</td><td>{@code upgrade-fixture}</td>
     *       <td>Order: nap 1 s, express 10 min, cancellable 1 ms</td>
     *       <td>WAITING_SIGNAL (step 8)</td><td>the deadline fires on this build (SIGNAL_TIMEOUT)</td></tr>
     *   <tr><td>{@code seed-parked-approval}</td><td>{@code upgrade-fixture}</td>
     *       <td>as {@code seed-completed}, no approval</td>
     *       <td>WAITING_SIGNAL (step 10)</td><td>replays steps 1–9, including SIGNAL_TIMEOUT and TIMER_CANCELLED</td></tr>
     *   <tr><td>{@code seed-crashed}</td><td>{@code upgrade-fixture}</td>
     *       <td>as {@code seed-completed}; approval delivered; {@code charge} wedged</td>
     *       <td>RUNNING</td><td>crash recovery; {@code charge} never recorded</td></tr>
     *   <tr><td>{@code seed-terminated}</td><td>{@code upgrade-fixture}</td>
     *       <td>as {@code seed-parked-approval}, then {@code terminateWorkflow}</td>
     *       <td>TERMINATED</td><td>never adopted</td></tr>
     *   <tr><td>{@code seed-saga-compensated}</td><td>{@code upgrade-fixture}</td>
     *       <td>Saga, item {@code "plain"}</td>
     *       <td>FAILED</td><td>COMPENSATION_STARTED / STEP_COMPLETED ×2 / COMPLETED; WP3 retry</td></tr>
     *   <tr><td>{@code seed-saga-compensation-failed}</td><td>{@code upgrade-fixture}</td>
     *       <td>Saga, item {@code "fail-refund:x"}</td>
     *       <td>FAILED</td><td>COMPENSATION_STEP_FAILED; WP3</td></tr>
     *   <tr><td>{@code seed-compensating-crashed}</td><td>{@code upgrade-fixture}</td>
     *       <td>Saga, item {@code "crash-compensation"}; {@code releaseReservation} wedged</td>
     *       <td>COMPENSATING</td><td>recovered mid-compensation (refund already recorded)</td></tr>
     *   <tr><td>{@code seed-activity-failed}</td><td>{@code upgrade-fixture}</td>
     *       <td>Failing, alert false</td>
     *       <td>FAILED</td><td>ACTIVITY_FAILED that ended the run; WP3 (3.3)</td></tr>
     *   <tr><td>{@code seed-alert-failed}</td><td>{@code upgrade-fixture}</td>
     *       <td>Failing, alert true</td>
     *       <td>FAILED</td><td>catch, alert, rethrow; WP3 (D10)</td></tr>
     *   <tr><td>{@code seed-orphan-target}</td><td>{@code upgrade-fixture}</td>
     *       <td>never started; {@code approval} and {@code note} delivered before start</td>
     *       <td>(signals only)</td><td>orphan adoption by this build</td></tr>
     *   <tr><td>{@code seed-wp1-decimal} / {@code -offset} / {@code -set}</td><td>{@code upgrade-fixture-wp1}</td>
     *       <td>Typed, kind DECIMAL / OFFSET / SET</td>
     *       <td>WAITING_SIGNAL</td><td>dormant: WP9 never adopts them; reserved for WP1 (C1, 3.8)</td></tr>
     * </table>
     *
     * <p>Every Order instance got its {@code note} signal right after it
     * started: payload {@code "note for " + id}, message id
     * {@code "client:" + id + ":note"}, so each non-completed one holds a
     * signal written at {@code cbf5a2a} that is still unconsumed. Approvals
     * delivered at capture carry {@code Approval("capture", 1)} and no message
     * id. Every Order uses the skus {@link #SKUS}.
     */
    public static final class Seeds {

        private Seeds() {
        }

        /** Service of every seeded workflow except the WP1 ones. */
        public static final String SERVICE = "upgrade-fixture";
        /** Service of the dormant WP1 workflows; WP9 never runs a node of it. */
        public static final String WP1_SERVICE = "upgrade-fixture-wp1";

        /** Skus of every seeded Order, in order: two parallel {@code reserve} branches. */
        public static final List<String> SKUS = List.of("sku-red", "sku-blue");

        /** Order, run to completion. */
        public static final String COMPLETED_RUN = "seed-completed";
        /** Order parked on its 10-minute nap (step 7). */
        public static final String PARKED_TIMER = "seed-parked-timer";
        /** Order parked on its 10-minute {@code express} await (step 8). */
        public static final String PARKED_EXPRESS = "seed-parked-express";
        /** Order parked on {@code approval} (step 10). */
        public static final String PARKED_APPROVAL = "seed-parked-approval";
        /** Order whose {@code charge} was in flight when the history was dumped. */
        public static final String CRASHED = "seed-crashed";
        /** Order parked on {@code approval}, then terminated. */
        public static final String TERMINATED_RUN = "seed-terminated";
        /** Saga whose two compensations both succeeded. */
        public static final String SAGA_COMPENSATED = "seed-saga-compensated";
        /** Saga whose {@code refund} compensation failed. */
        public static final String SAGA_COMPENSATION_FAILED = "seed-saga-compensation-failed";
        /** Saga whose {@code releaseReservation} compensation was in flight when dumped. */
        public static final String COMPENSATING_CRASHED = "seed-compensating-crashed";
        /** Failing workflow that rethrew its activity failure. */
        public static final String ACTIVITY_FAILED = "seed-activity-failed";
        /** Failing workflow that ran {@code alert} before rethrowing. */
        public static final String ALERT_FAILED = "seed-alert-failed";
        /** Never started: holds only the {@code approval} and {@code note} signals sent to it. */
        public static final String ORPHAN_TARGET = "seed-orphan-target";
        /** Typed workflow, kind DECIMAL, parked on {@code go}. */
        public static final String WP1_DECIMAL = "seed-wp1-decimal";
        /** Typed workflow, kind OFFSET, parked on {@code go}. */
        public static final String WP1_OFFSET = "seed-wp1-offset";
        /** Typed workflow, kind SET, parked on {@code go}. */
        public static final String WP1_SET = "seed-wp1-set";

        /** The 1-day cancellable nap, cancelled at capture: far above any test bound. */
        public static final long ONE_DAY_MILLIS = Duration.ofDays(1).toMillis();
        /** The 10-minute wait of the two parked-at-capture runs. */
        public static final long TEN_MINUTES_MILLIS = Duration.ofMinutes(10).toMillis();

        /** Status of every started seed id when the history was dumped. */
        public static final Map<String, WorkflowStatus> STATUS_AT_CAPTURE;

        static {
            var statuses = new LinkedHashMap<String, WorkflowStatus>();
            statuses.put(COMPLETED_RUN, WorkflowStatus.COMPLETED);
            statuses.put(PARKED_TIMER, WorkflowStatus.WAITING_TIMER);
            statuses.put(PARKED_EXPRESS, WorkflowStatus.WAITING_SIGNAL);
            statuses.put(PARKED_APPROVAL, WorkflowStatus.WAITING_SIGNAL);
            statuses.put(CRASHED, WorkflowStatus.RUNNING);
            statuses.put(TERMINATED_RUN, WorkflowStatus.TERMINATED);
            statuses.put(SAGA_COMPENSATED, WorkflowStatus.FAILED);
            statuses.put(SAGA_COMPENSATION_FAILED, WorkflowStatus.FAILED);
            statuses.put(COMPENSATING_CRASHED, WorkflowStatus.COMPENSATING);
            statuses.put(ACTIVITY_FAILED, WorkflowStatus.FAILED);
            statuses.put(ALERT_FAILED, WorkflowStatus.FAILED);
            statuses.put(WP1_DECIMAL, WorkflowStatus.WAITING_SIGNAL);
            statuses.put(WP1_OFFSET, WorkflowStatus.WAITING_SIGNAL);
            statuses.put(WP1_SET, WorkflowStatus.WAITING_SIGNAL);
            STATUS_AT_CAPTURE = Collections.unmodifiableMap(statuses);
        }

        /**
         * Every event type the seeded history holds: every {@link EventType}
         * of {@code cbf5a2a} except {@code UNKNOWN}, {@code WORKFLOW_STARTED}
         * and {@code ACTIVITY_STARTED}, which that engine publishes only as
         * lifecycle events and never stores. Listed by name, not derived from
         * the enum, so a type a later build adds is not expected in history
         * written before it existed.
         */
        public static final Set<EventType> EVENT_TYPES = Collections.unmodifiableSet(EnumSet.of(
                EventType.ACTIVITY_COMPLETED,
                EventType.ACTIVITY_FAILED,
                EventType.SIGNAL_RECEIVED,
                EventType.TIMER_SCHEDULED,
                EventType.TIMER_FIRED,
                EventType.TIMER_CANCELLED,
                EventType.COMPENSATION_STARTED,
                EventType.COMPENSATION_COMPLETED,
                EventType.COMPENSATION_STEP_COMPLETED,
                EventType.COMPENSATION_STEP_FAILED,
                EventType.WORKFLOW_COMPLETED,
                EventType.WORKFLOW_FAILED,
                EventType.SIDE_EFFECT,
                EventType.SIGNAL_TIMEOUT,
                EventType.VERSION_MARKER));

        /** Every activity step key, {@code "<activity name>.<method>"}. */
        public static final Set<String> STEPS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
                "seed-order.quote",
                "seed-order.reserve",
                "seed-order.charge",
                "seed-risk.verify",
                "seed-saga.reserve",
                "seed-saga.releaseReservation",
                "seed-saga.charge",
                "seed-saga.refund",
                "seed-saga.ship",
                "seed-failing.explode",
                "seed-failing.alert",
                "seed-typed.exactAmount",
                "seed-typed.localStamp",
                "seed-typed.tags",
                "seed-typed.settleAmount",
                "seed-typed.settleStamp",
                "seed-typed.settleTags")));
    }

    // ── Observation seams ─────────────────────────────────────────────────

    /**
     * Where an activity stops before doing its work: the capture holds two
     * steps here to leave a run mid-activity, and a replay test can hold one
     * to observe it. {@link #OPEN} never blocks.
     */
    @FunctionalInterface
    public interface StepGate {

        /**
         * Called by every activity method after recording its invocation.
         *
         * @param step the step key, {@code "<activity name>.<method>"}
         * @param key  the business key of the call (order id, sku, item or
         *             failing key; empty for a no-argument method)
         */
        void enter(String step, String key);

        /** A gate that never blocks. */
        StepGate OPEN = (s, k) -> { };
    }

    /**
     * Counts activity invocations per step key, so a test can tell a replayed
     * step (count unchanged) from a re-executed one.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent use: parallel branches record from several
     * virtual threads at once.
     */
    public static final class Invocations {

        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        /** Creates a counter with every step of {@link Seeds#STEPS} at zero. */
        public Invocations() {
            for (var step : Seeds.STEPS) {
                counts.put(step, new AtomicInteger());
            }
        }

        /**
         * Records one invocation.
         *
         * @param step a step key of {@link Seeds#STEPS}
         * @return the count after recording
         * @throws IllegalArgumentException for an unknown step key
         */
        public int record(String step) {
            return counter(step).incrementAndGet();
        }

        /**
         * @param step a step key of {@link Seeds#STEPS}
         * @return how many times the step ran
         * @throws IllegalArgumentException for an unknown step key
         */
        public int count(String step) {
            return counter(step).get();
        }

        /** @return every step key with its count, zero included, sorted by key */
        public Map<String, Integer> snapshot() {
            var copy = new TreeMap<String, Integer>();
            counts.forEach((step, count) -> copy.put(step, count.get()));
            return Collections.unmodifiableMap(copy);
        }

        private AtomicInteger counter(String step) {
            var counter = counts.get(step);
            if (counter == null) {
                throw new IllegalArgumentException("Unknown step '" + step + "'; known: " + Seeds.STEPS);
            }
            return counter;
        }
    }

    // ── Payloads ──────────────────────────────────────────────────────────

    /**
     * Input of {@link SeedOrderWorkflow}.
     *
     * @param orderId              the order id (the seed's workflow id)
     * @param skus                 one parallel {@code reserve} branch per sku
     * @param napMillis            step 7's sleep
     * @param expressWaitMillis    step 8's {@code express} await timeout
     * @param cancellableNapMillis step 9's sleep, which the capture may cancel
     */
    public record OrderRequest(String orderId, List<String> skus, long napMillis, long expressWaitMillis,
                               long cancellableNapMillis) {
    }

    /**
     * One quoted line.
     *
     * @param sku      the sku
     * @param quantity the quantity
     * @param price    the unit price, two decimal places
     */
    public record QuoteLine(String sku, int quantity, BigDecimal price) {
    }

    /**
     * Result of {@code seed-order.quote}.
     *
     * @param orderId    the order id
     * @param total      sum of the lines, two decimal places
     * @param currency   the currency code
     * @param lines      one line per sku
     * @param validUntil always {@link SeedActivities#QUOTE_VALID_UNTIL}
     */
    public record Quote(String orderId, BigDecimal total, String currency, List<QuoteLine> lines,
                        Instant validUntil) {
    }

    /**
     * Payload of the {@code approval} signal.
     *
     * @param approver who approved
     * @param level    the approval level
     */
    public record Approval(String approver, int level) {
    }

    /**
     * Result of {@code seed-order.charge}.
     *
     * @param orderId  the order id
     * @param chargeId derived from the order id and approval level
     * @param amount   the quote total
     */
    public record Receipt(String orderId, String chargeId, BigDecimal amount) {
    }

    /**
     * Result of {@link SeedOrderWorkflow}.
     *
     * @param orderId       the order id
     * @param receipt       step 11's receipt
     * @param reservations  step 5's branch results, in branch order
     * @param verified      whether step 6's risk check passed (never, at capture)
     * @param napCancelled  whether step 9's sleep was cancelled
     * @param note          step 12's {@code note} payload
     * @param token         step 3's {@code randomUUID()}
     * @param decidedAt     step 2's {@code currentTime()}
     * @param version       step 4's {@code version("seed-change", …)}
     * @param approver      step 10's approver
     */
    public record OrderResult(String orderId, Receipt receipt, List<String> reservations, boolean verified,
                              boolean napCancelled, String note, String token, Instant decidedAt, int version,
                              String approver) {
    }

    /**
     * Input of {@link SeedFailingWorkflow}.
     *
     * @param key                the failing key (the seed's workflow id)
     * @param alertBeforeRethrow whether to run {@code alert} before rethrowing
     */
    public record FailingRequest(String key, boolean alertBeforeRethrow) {
    }

    /**
     * Input of {@link SeedTypedResultWorkflow}.
     *
     * @param kind {@code DECIMAL}, {@code OFFSET} or {@code SET}
     */
    public record TypedRequest(String kind) {
    }

    /** The fixture's activity failure: a public {@code (String)} constructor, so it replays as itself. */
    public static final class SeedFailure extends RuntimeException {

        /** @param message the message */
        public SeedFailure(String message) {
            super(message);
        }
    }

    // ── Activity interfaces ───────────────────────────────────────────────

    /** Order activities. */
    @Activity(name = "seed-order")
    public interface SeedOrderActivities {

        /**
         * @param request the order
         * @return a quote for its skus
         */
        Quote quote(OrderRequest request);

        /**
         * @param sku the sku
         * @return the reservation reference
         */
        String reserve(String sku);

        /**
         * @param quote    step 1's quote
         * @param approval step 10's approval
         * @return the receipt
         */
        Receipt charge(Quote quote, Approval approval);
    }

    /** Risk activities: {@code verify} always fails. */
    @Activity(name = "seed-risk")
    public interface SeedRiskActivities {

        /**
         * @param orderId the order id
         * @throws SeedFailure always
         */
        void verify(String orderId);
    }

    /** Saga activities: every forward step but {@code ship} has a compensation. */
    @Activity(name = "seed-saga")
    public interface SeedSagaActivities {

        /**
         * @param item the item
         * @return the reservation reference
         */
        @Compensate("releaseReservation")
        String reserve(String item);

        /** @param item the item whose reservation is released */
        void releaseReservation(String item);

        /**
         * @param item the item
         * @return the charge reference
         */
        @Compensate("refund")
        String charge(String item);

        /**
         * @param item the item to refund
         * @throws SeedFailure when {@code item} starts with {@code "fail-refund:"}
         */
        void refund(String item);

        /**
         * @param item the item
         * @return never returns
         * @throws SeedFailure always
         */
        String ship(String item);
    }

    /** Failing activities. */
    @Activity(name = "seed-failing")
    public interface SeedFailingActivities {

        /**
         * @param key the failing key
         * @return {@code "exploded:" + key} once explosions are switched off
         * @throws SeedFailure while explosions are switched on
         */
        String explode(String key);

        /**
         * @param key the failing key
         * @return {@code "alerted:" + key}
         */
        String alert(String key);
    }

    /** Activities whose results stress payload typing (WP1). */
    @Activity(name = "seed-typed")
    public interface SeedTypedActivities {

        /** @return {@code 12345678901234567890.123456789} */
        BigDecimal exactAmount();

        /** @return {@code 2026-11-12T10:00+02:00} */
        OffsetDateTime localStamp();

        /** @return {@code {alpha, beta, gamma, delta}} */
        Set<String> tags();

        /**
         * @param amount {@link #exactAmount()}'s value
         * @return {@code "amount:" + amount.toPlainString()}
         */
        String settleAmount(BigDecimal amount);

        /**
         * @param stamp {@link #localStamp()}'s value
         * @return {@code "stamp:" + stamp}
         */
        String settleStamp(OffsetDateTime stamp);

        /**
         * @param tags {@link #tags()}'s value
         * @return {@code "tags:"} followed by the sorted tags
         */
        String settleTags(Set<String> tags);
    }

    // ── The one activity implementation ───────────────────────────────────

    /**
     * Implements every fixture activity. Each method records its invocation
     * in {@link Invocations}, calls {@link StepGate#enter}, then returns a
     * value that depends only on its arguments (or throws {@link SeedFailure}).
     *
     * <h2>Thread Safety</h2>
     * <p>Stateless apart from the thread-safe {@link Invocations} and the
     * gate; safe for concurrent use.
     */
    public static final class SeedActivities implements SeedOrderActivities, SeedRiskActivities,
            SeedSagaActivities, SeedFailingActivities, SeedTypedActivities {

        /** Every quote's {@code validUntil}. */
        public static final Instant QUOTE_VALID_UNTIL = Instant.parse("2027-01-01T00:00:00Z");

        private final Invocations invocations;
        private final StepGate gate;
        private final boolean explodeFails;

        /**
         * @param invocations  records every call
         * @param gate         entered by every call before it does its work
         * @param explodeFails whether {@code explode} throws
         */
        public SeedActivities(Invocations invocations, StepGate gate, boolean explodeFails) {
            this.invocations = invocations;
            this.gate = gate;
            this.explodeFails = explodeFails;
        }

        private void enter(String step, String key) {
            invocations.record(step);
            gate.enter(step, key);
        }

        @Override
        public Quote quote(OrderRequest request) {
            enter("seed-order.quote", request.orderId());
            var lines = new ArrayList<QuoteLine>();
            var total = BigDecimal.ZERO.setScale(2);
            for (var sku : request.skus()) {
                var price = BigDecimal.valueOf(sku.length() * 1000L + 99, 2);
                lines.add(new QuoteLine(sku, 1, price));
                total = total.add(price);
            }
            return new Quote(request.orderId(), total, "EUR", List.copyOf(lines), QUOTE_VALID_UNTIL);
        }

        @Override
        public String reserve(String skuOrItem) {
            // One name, two interfaces: SeedOrderActivities.reserve(sku) and
            // SeedSagaActivities.reserve(item). Saga items never start with "sku-".
            if (skuOrItem.startsWith("sku-")) {
                enter("seed-order.reserve", skuOrItem);
                return "reservation:" + skuOrItem;
            }
            enter("seed-saga.reserve", skuOrItem);
            return "reserved:" + skuOrItem;
        }

        @Override
        public Receipt charge(Quote quote, Approval approval) {
            enter("seed-order.charge", quote.orderId());
            return new Receipt(quote.orderId(), "charge:" + quote.orderId() + ":" + approval.level(),
                    quote.total());
        }

        @Override
        public void verify(String orderId) {
            enter("seed-risk.verify", orderId);
            throw new SeedFailure("risk check unavailable for " + orderId);
        }

        @Override
        public void releaseReservation(String item) {
            enter("seed-saga.releaseReservation", item);
        }

        @Override
        public String charge(String item) {
            enter("seed-saga.charge", item);
            return "charged:" + item;
        }

        @Override
        public void refund(String item) {
            enter("seed-saga.refund", item);
            if (item.startsWith("fail-refund:")) {
                throw new SeedFailure("refund rejected for " + item);
            }
        }

        @Override
        public String ship(String item) {
            enter("seed-saga.ship", item);
            throw new SeedFailure("no carrier for " + item);
        }

        @Override
        public String explode(String key) {
            enter("seed-failing.explode", key);
            if (explodeFails) {
                throw new SeedFailure("exploded on " + key);
            }
            return "exploded:" + key;
        }

        @Override
        public String alert(String key) {
            enter("seed-failing.alert", key);
            return "alerted:" + key;
        }

        @Override
        public BigDecimal exactAmount() {
            enter("seed-typed.exactAmount", "");
            return new BigDecimal("12345678901234567890.123456789");
        }

        @Override
        public OffsetDateTime localStamp() {
            enter("seed-typed.localStamp", "");
            return OffsetDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneOffset.ofHours(2));
        }

        @Override
        public Set<String> tags() {
            enter("seed-typed.tags", "");
            return Set.of("alpha", "beta", "gamma", "delta");
        }

        @Override
        public String settleAmount(BigDecimal amount) {
            enter("seed-typed.settleAmount", amount.toPlainString());
            return "amount:" + amount.toPlainString();
        }

        @Override
        public String settleStamp(OffsetDateTime stamp) {
            enter("seed-typed.settleStamp", stamp.toString());
            return "stamp:" + stamp;
        }

        @Override
        public String settleTags(Set<String> tags) {
            var sorted = String.join(",", new TreeSet<>(tags));
            enter("seed-typed.settleTags", sorted);
            return "tags:" + sorted;
        }
    }

    // ── Workflows ─────────────────────────────────────────────────────────

    /**
     * Thirteen steps covering every non-saga event type: an activity, both
     * side effects, a version marker, parallel branches, a caught activity
     * failure, a fired sleep, a timed-out await, a cancellable sleep, two
     * received signals and a final activity.
     */
    @DurableWorkflow(name = "SeedOrderWorkflow")
    public static class SeedOrderWorkflow {

        @ActivityStub(startToCloseTimeout = "PT10M")
        SeedOrderActivities order;

        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        SeedRiskActivities risk;

        /**
         * @param request the order
         * @return the outcome of every step
         */
        @WorkflowMethod
        public OrderResult run(OrderRequest request) {
            var ctx = WorkflowContext.current();
            // 1
            Quote quote = order.quote(request);
            // 2
            Instant decidedAt = ctx.currentTime();
            // 3
            String token = ctx.randomUUID();
            // 4
            int version = ctx.version("seed-change", WorkflowContext.DEFAULT_VERSION, 1);
            // 5
            List<Callable<String>> branches = new ArrayList<>();
            for (var sku : request.skus()) {
                branches.add(() -> order.reserve(sku));
            }
            List<String> reservations = ctx.parallel(branches);
            // 6
            boolean verified;
            try {
                risk.verify(request.orderId());
                verified = true;
            } catch (ActivityExecutionException e) {
                verified = false;
            }
            // 7
            ctx.sleep(Duration.ofMillis(request.napMillis()));
            // 8
            try {
                ctx.awaitSignal("express", String.class, Duration.ofMillis(request.expressWaitMillis()));
            } catch (SignalTimeoutException e) {
                // no express upgrade: carry on
            }
            // 9
            boolean napCancelled;
            try {
                ctx.sleep(Duration.ofMillis(request.cancellableNapMillis()));
                napCancelled = false;
            } catch (TimerCancelledException e) {
                napCancelled = true;
            }
            // 10
            Approval approval = ctx.awaitSignal("approval", Approval.class, Duration.ofDays(3650));
            // 11
            Receipt receipt = order.charge(quote, approval);
            // 12
            String note = ctx.awaitSignal("note", String.class, Duration.ofDays(3650));
            // 13
            return new OrderResult(request.orderId(), receipt, reservations, verified, napCancelled, note,
                    token, decidedAt, version, approval.approver());
        }
    }

    /** A saga whose last step always fails, so both compensations run in LIFO order. */
    @DurableWorkflow(name = "SeedSagaWorkflow")
    public static class SeedSagaWorkflow {

        @ActivityStub(startToCloseTimeout = "PT10M", retryPolicy = @RetryPolicy(maxAttempts = 1))
        SeedSagaActivities saga;

        /**
         * @param item the item
         * @return never returns: {@code ship} always fails
         */
        @Saga
        @WorkflowMethod
        public String run(String item) {
            saga.reserve(item);
            saga.charge(item);
            return saga.ship(item);
        }
    }

    /** Catches an activity failure, optionally runs {@code alert}, then rethrows it. */
    @DurableWorkflow(name = "SeedFailingWorkflow")
    public static class SeedFailingWorkflow {

        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 2, initialInterval = "PT0.1S"))
        SeedFailingActivities failing;

        /**
         * @param request the failing key and whether to alert
         * @return {@code explode}'s result once explosions are switched off
         */
        @WorkflowMethod
        public String run(FailingRequest request) {
            try {
                return failing.explode(request.key());
            } catch (ActivityExecutionException e) {
                if (request.alertBeforeRethrow()) {
                    failing.alert(request.key());
                }
                throw e;
            }
        }
    }

    /** Records a typed activity result, parks on {@code go}, then hands the result back to an activity. */
    @DurableWorkflow(name = "SeedTypedResultWorkflow")
    public static class SeedTypedResultWorkflow {

        @ActivityStub
        SeedTypedActivities typed;

        /**
         * @param request which typed result to record
         * @return the settling activity's result
         */
        @WorkflowMethod
        public String run(TypedRequest request) {
            var ctx = WorkflowContext.current();
            return switch (request.kind()) {
                case "DECIMAL" -> {
                    BigDecimal amount = typed.exactAmount();
                    ctx.awaitSignal("go", String.class, Duration.ofDays(3650));
                    yield typed.settleAmount(amount);
                }
                case "OFFSET" -> {
                    OffsetDateTime stamp = typed.localStamp();
                    ctx.awaitSignal("go", String.class, Duration.ofDays(3650));
                    yield typed.settleStamp(stamp);
                }
                case "SET" -> {
                    Set<String> tags = typed.tags();
                    ctx.awaitSignal("go", String.class, Duration.ofDays(3650));
                    yield typed.settleTags(tags);
                }
                default -> throw new IllegalArgumentException("Unknown kind " + request.kind());
            };
        }
    }
}
