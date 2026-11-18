package io.b2mash.maestro.core.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown at startup when this service's Maestro tables hold active workflow
 * instances recorded under another {@code maestro.service-name}: either
 * another service shares the tables, which is not supported (services never
 * share Maestro tables — {@code docs/support-matrix.md}), or a rename left
 * workflows under the old name, where recovery never adopts them.
 *
 * <p>Raised by {@code WorkflowExecutor.checkForeignServices} under
 * {@code ForeignServiceCheckMode.FAIL}, the default; the Spring Boot starter
 * runs that check at context refresh, so the context fails to start.
 * {@code maestro.store.foreign-service-check=warn} downgrades it to a warning
 * for a rename in progress.
 *
 * <p><b>Thread safety:</b> immutable once constructed; safe to share across
 * threads.
 */
public final class ForeignServiceRowsException extends MaestroException {

    private final Map<String, Long> counts;

    /**
     * Creates the exception.
     *
     * @param message the startup failure message, naming every other service
     *                name, its count and the remedy
     * @param counts  active instances per other service name, in the order
     *                the store returned them; copied
     */
    public ForeignServiceRowsException(String message, Map<String, Long> counts) {
        super(message);
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /**
     * The active instances found per other service name, by count descending
     * (at most 20 names, the most the check reports).
     *
     * @return an unmodifiable map; never {@code null} or empty
     */
    public Map<String, Long> counts() {
        return counts;
    }
}
