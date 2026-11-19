package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ForeignServiceRowsException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The once-per-start shared-table check (pilot readiness P3; Issues 39, 79).
 *
 * <p>Services never share Maestro tables ({@code docs/support-matrix.md}).
 * Recovery adopts only the active instances recorded under this node's own
 * {@code maestro.service-name}, so an active row under any other name is
 * either another service in the same tables or a workflow stranded by a
 * rename. This check asks the store once, through
 * {@link WorkflowStore#getActiveInstanceCountsUnderOtherServices(String, int)},
 * for every other name holding active instances, of any workflow type.
 *
 * <ul>
 *   <li><b>Bounded.</b> The read runs on its own virtual thread,
 *       {@link #THREAD_NAME}, and the caller waits for it at most
 *       {@link #PROBE_TIMEOUT}. A read still running then is left to finish on
 *       its own thread — never interrupted, since it may be inside a JDBC
 *       call.</li>
 *   <li><b>Inconclusive is never fatal.</b> A read that fails or does not
 *       answer in time logs one {@code WARN} and startup continues, so an
 *       unreachable store cannot turn into a restart loop (the rule
 *       {@code KafkaTopicVerifier} follows). A
 *       {@link MaestroControlFlowError} is rethrown, as everywhere in the
 *       engine.</li>
 *   <li><b>Rows found</b> fail startup under {@link ForeignServiceCheckMode#FAIL}
 *       with {@link ForeignServiceRowsException}, or log one {@code WARN} per
 *       name (at most {@link #MAX_REPORTED}) under {@link ForeignServiceCheckMode#WARN}.
 *       A clean result logs one {@code DEBUG} line.</li>
 * </ul>
 *
 * <p>It writes nothing and never logs a workflow id.
 *
 * <p><b>Thread safety:</b> stateless; every method may be called from any
 * thread, concurrently.
 */
final class ForeignServiceCheck {

    private static final Logger logger = LoggerFactory.getLogger(ForeignServiceCheck.class);

    /** The most foreign service names the check reports. */
    static final int MAX_REPORTED = 20;

    /** Name of the virtual thread the read runs on. */
    static final String THREAD_NAME = "maestro-foreign-service-check";

    /** How long startup waits for the read, as {@code KafkaTopicVerifier} waits for its probe. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** The property that sets the mode; named in every message. */
    static final String PROPERTY = "maestro.store.foreign-service-check";

    /**
     * The outcome of one read.
     *
     * @param foreign      active instances per other service name, by count
     *                     descending; empty when there are none or the read
     *                     was inconclusive
     * @param inconclusive why the read gave no answer, or {@code null} when it did
     */
    record Result(Map<String, Long> foreign, @Nullable String inconclusive) {
    }

    private ForeignServiceCheck() {
    }

    /**
     * Runs the check once and acts on it as {@code mode} says.
     *
     * @param store       the store to ask
     * @param serviceName this node's {@code maestro.service-name}
     * @param mode        what to do with rows found; {@link ForeignServiceCheckMode#OFF} reads nothing
     * @param timeout     how long to wait for the read
     * @throws ForeignServiceRowsException under {@code FAIL} when rows were found
     * @throws MaestroControlFlowError     if the read threw one
     */
    static void run(WorkflowStore store, String serviceName, ForeignServiceCheckMode mode, Duration timeout) {
        if (mode == ForeignServiceCheckMode.OFF) {
            logger.debug("Maestro shared-table check off ({}=off)", PROPERTY);
            return;
        }
        var result = probe(store, serviceName, timeout);
        if (result.inconclusive() != null) {
            logger.warn("Maestro shared-table check inconclusive: {}. Startup continues; the next start checks "
                    + "again ({}).", result.inconclusive(), PROPERTY);
            return;
        }
        var foreign = result.foreign();
        if (foreign.isEmpty()) {
            logger.debug("Maestro shared-table check: no other service name holds active instances");
            return;
        }
        if (mode == ForeignServiceCheckMode.FAIL) {
            throw new ForeignServiceRowsException(failure(serviceName, foreign), foreign);
        }
        foreign.entrySet().stream().limit(MAX_REPORTED)
                .forEach(e -> logger.warn(warning(serviceName, e.getKey(), e.getValue())));
        if (foreign.size() > MAX_REPORTED) {
            logger.info("More than {} other service names hold active instances; docs/operations.md §11.5 "
                    + "lists them all.", MAX_REPORTED);
        }
    }

    /**
     * One bounded read on {@link #THREAD_NAME}: asks for at most
     * {@link #MAX_REPORTED}{@code + 1} names and waits at most {@code timeout}.
     *
     * @return the counts, or an inconclusive result; never {@code null}
     * @throws MaestroControlFlowError if the read threw one
     */
    static Result probe(WorkflowStore store, String serviceName, Duration timeout) {
        var answer = new CompletableFuture<Map<String, Long>>();
        Thread.ofVirtual().name(THREAD_NAME).start(() -> {
            try {
                answer.complete(store.getActiveInstanceCountsUnderOtherServices(serviceName, MAX_REPORTED + 1));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        });
        try {
            return new Result(answer.get(timeout.toNanos(), TimeUnit.NANOSECONDS), null);
        } catch (TimeoutException e) {
            return new Result(Map.of(), "the store did not answer within " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Map.of(), "interrupted while waiting for the store");
        } catch (ExecutionException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            // Error before Exception, and a control-flow Error is never swallowed.
            if (cause instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            // The throwable's text only: a stack trace would make an
            // inconclusive check look like a startup failure.
            return new Result(Map.of(), "the read failed: " + cause);
        }
    }

    /**
     * The startup failure for rows found under {@link ForeignServiceCheckMode#FAIL}.
     *
     * @param ownService this node's service name
     * @param foreign    active instances per other service name
     * @return the message
     */
    static String failure(String ownService, Map<String, Long> foreign) {
        var names = foreign.entrySet().stream().limit(MAX_REPORTED)
                .map(e -> "'" + e.getKey() + "' (" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
        var more = foreign.size() > MAX_REPORTED ? " and more" : "";
        return "Maestro's tables hold active workflow instances recorded under another maestro.service-name than '"
                + ownService + "': " + names + more + ". Services never share Maestro tables "
                + "(docs/support-matrix.md): give each service its own database or schema. If a node of this "
                + "service ran under another name, or the service is being renamed, recovery adopts only '"
                + ownService + "' instances; set " + PROPERTY + "=warn until the old name's instances are "
                + "drained or moved (docs/operations.md §11.5).";
    }

    /**
     * The warning for one foreign service name, under {@link ForeignServiceCheckMode#WARN}.
     *
     * @param ownService     this node's service name
     * @param foreignService the other service name
     * @param count          its active instances
     * @return the log message
     */
    static String warning(String ownService, String foreignService, long count) {
        return count + " active workflow instance(s) are recorded under maestro.service-name '" + foreignService
                + "', not '" + ownService + "'. Recovery adopts only '" + ownService + "' instances, so unless a "
                + "node runs with maestro.service-name=" + foreignService + " they never resume; finish the "
                + "rename and set " + PROPERTY + " back to fail (docs/operations.md §11.5).";
    }
}
