/**
 * Workflow execution engine — activity proxy, memoization, and orchestration.
 *
 * <p>The core class is {@link io.b2mash.maestro.core.engine.ActivityInvocationHandler},
 * which intercepts activity method calls to implement hybrid memoization:
 * checking for stored results on replay and persisting new results on
 * live execution.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 *
 * @see io.b2mash.maestro.core.engine.ActivityInvocationHandler
 * @see io.b2mash.maestro.core.engine.ActivityProxyFactory
 * @see io.b2mash.maestro.core.engine.PayloadSerializer
 */
@NullMarked
package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.NullMarked;
