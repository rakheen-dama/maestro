package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * The once-per-start advisory check for workflows recorded under another
 * {@code maestro.service-name} (Issue 39).
 *
 * <p>Recovery adopts only the active instances recorded under this node's
 * own service name. If the service was renamed, or one node ran with a
 * different name, active instances of this node's workflow types can sit
 * under a name no node runs, and they never resume. This check asks the
 * store once, through
 * {@link WorkflowStore#getActiveInstanceCountsUnderOtherServices}, and logs
 * one {@code WARN} per such name with its count and the remedy. It reads
 * nothing else, writes nothing, and never logs a workflow id.
 *
 * <ul>
 *   <li>At most {@link #MAX_REPORTED} names are warned about. The store is
 *       asked for one more; if it returns more than {@code MAX_REPORTED}, one
 *       {@code INFO} says so.</li>
 *   <li>A clean result logs one {@code DEBUG} line and nothing above it. So
 *       does an empty type set, which makes no store call.</li>
 *   <li>A failure is logged once at {@code INFO}, without a stack trace, and
 *       swallowed: the check is advisory and must never fail or delay
 *       startup. A {@link MaestroControlFlowError} is rethrown, as everywhere
 *       in the engine.</li>
 * </ul>
 *
 * <p>{@link WorkflowExecutor#startForeignServiceCheck} runs it once, on the
 * virtual thread {@link #THREAD_NAME}.
 *
 * <p><b>Thread safety:</b> stateless; {@link #run} may be called from any
 * thread, concurrently.
 */
final class ForeignServiceCheck {

    private static final Logger logger = LoggerFactory.getLogger(ForeignServiceCheck.class);

    /** The most foreign service names the check warns about. */
    static final int MAX_REPORTED = 20;

    /** Name of the virtual thread the check runs on. */
    static final String THREAD_NAME = "maestro-foreign-service-check";

    private ForeignServiceCheck() {
    }

    /**
     * Runs the check once on the calling thread: one store read, then the
     * log lines described in the class Javadoc.
     *
     * @param store         the store to ask
     * @param serviceName   this node's {@code maestro.service-name}
     * @param workflowTypes the workflow types this node registers
     * @return the counts the store returned (empty when there are no types,
     *         or the read failed); never {@code null}
     * @throws MaestroControlFlowError if the read threw one; nothing else is thrown
     */
    static Map<String, Long> run(WorkflowStore store, String serviceName, Set<String> workflowTypes) {
        if (workflowTypes.isEmpty()) {
            logger.debug("Maestro service-name check: no workflow types registered, nothing to check");
            return Map.of();
        }
        Map<String, Long> counts;
        try {
            counts = store.getActiveInstanceCountsUnderOtherServices(serviceName, workflowTypes, MAX_REPORTED + 1);
        } catch (Throwable t) {
            if (t instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            // The throwable's text only: a stack trace would make an advisory
            // read look like a startup failure.
            logger.info("Maestro service-name check skipped: {}", String.valueOf(t));
            return Map.of();
        }
        if (counts.isEmpty()) {
            logger.debug("Maestro service-name check: no other service name holds active instances of this "
                    + "node's {} workflow type(s)", workflowTypes.size());
            return counts;
        }
        counts.entrySet().stream().limit(MAX_REPORTED)
                .forEach(e -> logger.warn(warning(serviceName, e.getKey(), e.getValue())));
        if (counts.size() > MAX_REPORTED) {
            logger.info("More than {} other service names hold active instances of this node's workflow types; "
                    + "docs/operations.md §11.5 lists them all.", MAX_REPORTED);
        }
        return counts;
    }

    /**
     * The warning for one foreign service name.
     *
     * @param ownService     this node's service name
     * @param foreignService the other service name
     * @param count          its active instances of this node's types
     * @return the log message
     */
    static String warning(String ownService, String foreignService, long count) {
        return count + " active workflow instance(s) of a type this node runs are recorded under "
                + "maestro.service-name '" + foreignService + "', not '" + ownService + "'. Recovery adopts only '"
                + ownService + "' instances, so unless a node runs with maestro.service-name=" + foreignService
                + " these workflows never resume; if no node runs that name any more, start one under it or correct "
                + "the rows (docs/operations.md §11.5).";
    }
}
