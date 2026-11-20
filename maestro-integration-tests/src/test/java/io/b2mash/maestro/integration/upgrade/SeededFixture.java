package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One seeded-history fixture: the history one past build wrote, captured once
 * at one git ref (RELEASING.md § Upgrade fixtures), with everything a test
 * needs to load it and to drive its frozen workflows on this build.
 *
 * <h2>What a fixture is</h2>
 * <ul>
 *   <li><b>Resources</b>, under {@code src/test/resources/upgrade/<name>/}:
 *       {@code seeded-history.sql}, a {@code pg_dump} of every
 *       {@code maestro_*} table (DDL, {@code maestro_schema_history}, rows)
 *       taken after the capture driver ran the frozen workflows on that build;
 *       and {@code capture.properties}, which records the ref, the capture
 *       time, the server version, and the path and SHA-256 of the frozen
 *       workflow source.</li>
 *   <li><b>The frozen package</b> {@code io.b2mash.maestro.integration.upgrade.<name>}:
 *       {@code SeededHistoryWorkflows} (the workflows, activities, payloads and
 *       the {@code Seeds} catalogue that produced the dump) and
 *       {@code SeededHistoryCapture} (the driver). Never edited after
 *       capture.</li>
 *   <li><b>This interface's implementation</b>, outside the frozen package
 *       (it must not be copied into the capture checkout): the pinned
 *       SHA-256s, the clock window of the dump's timers, what the build at the
 *       ref recorded ({@link #recordedEvents()}), what this build is expected
 *       to append ({@link #appended()}), and adapters from the frozen package's
 *       own types to the types a shared test can hold.</li>
 * </ul>
 *
 * <p>Every fixture after {@code cbf5a2a} keeps every {@code cbf5a2a} seed,
 * under the same workflow id, service, workflow and input, so the shared
 * assertions of {@code SeededHistoryUpgradeIT} apply to each; a later fixture
 * may add seeds (in a service of their own, so the shared assertions do not
 * see them) and tests for them.
 *
 * <h2>Two kinds of pin</h2>
 * <p>{@link #dumpSha256()}, {@link #captureManifestSha256()}, the clock window,
 * {@link #appliedVersionsAtCapture()}, {@link #recordedEvents()},
 * {@link #statusAtCapture()} and {@link #eventTypes()} describe history the
 * older build wrote: never edit them. {@link #appended()} describes what
 * <em>this</em> build does on top of it, and may change only as a named step of
 * a plan that deliberately changes engine behaviour.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations are immutable.
 */
public interface SeededFixture {

    /** @return the fixture's name: its resource directory and its frozen package segment */
    String name();

    /** @return the full SHA of the commit the history was captured at */
    String ref();

    /** @return SHA-256 of the raw bytes of {@link #dumpResource()}, as captured */
    String dumpSha256();

    /**
     * @return SHA-256 of the raw bytes of {@link #manifestResource()}, as
     *         committed; pinning the manifest pins the frozen source too, since
     *         the manifest records that file's SHA-256
     */
    String captureManifestSha256();

    /**
     * @return the seeded {@code PENDING} timer rows a replay needs already
     *         due, as {@code "<workflowId>/<timerId>"}
     */
    Set<String> mustBeDue();

    /** @return the latest {@code fire_at} among {@link #mustBeDue()} */
    Instant latestMustBeDue();

    /**
     * @return the earliest {@code fire_at} among every other seeded
     *         {@code PENDING} timer row: from then on a timer poller would
     *         fire them
     */
    Instant earliestMustStayPending();

    /** @return the {@code maestro_schema_history} versions the dump records, in installed order */
    List<String> appliedVersionsAtCapture();

    /**
     * @return every event the older build wrote, per seeded workflow:
     *         {@code "<seq>:<type>"} in sequence order, derived from the dump by
     *         raw SQL over the restored tables
     */
    Map<String, String> recordedEvents();

    /**
     * @return what this build appends to each run the shared recovery test
     *         drives: {@code "<seq> <type> <step>"}
     */
    Map<String, List<String>> appended();

    /**
     * @return the seeded runs the shared recovery test must leave exactly as
     *         written: finished runs and runs of another service
     */
    List<String> untouchedByRecovery();

    /**
     * @return what this build appends to {@code seed-saga-parked} after the
     *         events the older build wrote ({@code SeededParkedSagaIT}), or an
     *         empty list for a fixture without that seed ({@code cbf5a2a})
     */
    default List<String> parkedSagaAppended() {
        return List.of();
    }

    /** @return the frozen {@code Seeds.STATUS_AT_CAPTURE} */
    Map<String, WorkflowStatus> statusAtCapture();

    /** @return the frozen {@code Seeds.EVENT_TYPES} */
    Set<EventType> eventTypes();

    /**
     * Builds the frozen {@code SeedActivities} with a fresh counter.
     *
     * @param gate         entered by every call with {@code (step, key)} before it does its work
     * @param explodeFails whether {@code seed-failing.explode} throws
     * @return the activities and their counter
     */
    FrozenActivities activities(StepGate gate, boolean explodeFails);

    /** @return the frozen {@code SeedOrderWorkflow} */
    Class<?> orderWorkflow();

    /** @return the frozen {@code SeedSagaWorkflow} */
    Class<?> sagaWorkflow();

    /** @return the frozen {@code SeedFailingWorkflow} */
    Class<?> failingWorkflow();

    /** @return every frozen workflow class, for registration on a node */
    List<Class<?>> workflows();

    /**
     * @param approver who approved
     * @param level    the approval level
     * @return a frozen {@code Approval}, the {@code approval} signal's payload
     */
    Object approval(String approver, int level);

    /**
     * @return a frozen {@code OrderRequest}
     */
    Object orderRequest(String orderId, List<String> skus, long napMillis, long expressWaitMillis,
                        long cancellableNapMillis);

    /** @return the frozen {@code OrderResult} class, to read a run's output as */
    Class<?> orderResultType();

    /**
     * @return a frozen {@code OrderResult} with a frozen {@code Receipt}
     *         {@code (orderId, chargeId, amount)}
     */
    Object orderResult(String orderId, String chargeId, BigDecimal amount, List<String> reservations,
                       boolean verified, boolean napCancelled, String note, String token, Instant decidedAt,
                       int version, String approver);

    /** @return {@code upgrade/<name>/seeded-history.sql} */
    default String dumpResource() {
        return "upgrade/" + name() + "/seeded-history.sql";
    }

    /** @return {@code upgrade/<name>/capture.properties} */
    default String manifestResource() {
        return "upgrade/" + name() + "/capture.properties";
    }

    /**
     * Where an activity stops before doing its work, as every frozen
     * {@code StepGate} does.
     */
    @FunctionalInterface
    interface StepGate {

        /**
         * @param step the step key, {@code "<activity name>.<method>"}
         * @param key  the business key of the call
         */
        void enter(String step, String key);

        /** A gate that never blocks. */
        StepGate OPEN = (s, k) -> { };
    }
}
