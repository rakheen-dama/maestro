/**
 * Annotations for declaring durable workflows and activities.
 *
 * <p>These annotations are processed at runtime by the Maestro engine
 * (or by the Spring Boot starter's bean post-processors) to create
 * activity proxies that implement hybrid memoization.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 *
 * @see io.b2mash.maestro.core.annotation.Activity
 * @see io.b2mash.maestro.core.annotation.ActivityStub
 */
@NullMarked
package io.b2mash.maestro.core.annotation;

import org.jspecify.annotations.NullMarked;
