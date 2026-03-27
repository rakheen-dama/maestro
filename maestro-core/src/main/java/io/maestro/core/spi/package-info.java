/**
 * Service Provider Interfaces (SPIs) for Maestro infrastructure backends.
 *
 * <p>These interfaces decouple the workflow engine from specific storage,
 * messaging, and coordination technologies:
 * <ul>
 *   <li>{@link io.maestro.core.spi.WorkflowStore} — persistent workflow state</li>
 *   <li>{@link io.maestro.core.spi.WorkflowMessaging} — task dispatch and signal delivery</li>
 *   <li>{@link io.maestro.core.spi.DistributedLock} — distributed coordination and leader election</li>
 * </ul>
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.maestro.core.spi;

import org.jspecify.annotations.NullMarked;
