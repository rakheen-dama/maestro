/**
 * Exception hierarchy for Maestro workflow engine errors.
 *
 * <p>All exceptions extend the sealed base class
 * {@link io.maestro.core.exception.MaestroException}, enabling exhaustive
 * pattern matching in Java 21+ {@code switch} expressions.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.maestro.core.exception;

import org.jspecify.annotations.NullMarked;
