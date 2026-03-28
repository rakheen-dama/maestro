/**
 * Spring annotations for the Maestro durable workflow engine.
 *
 * <p>Contains user-facing annotations that integrate with Maestro's
 * messaging infrastructure:
 * <ul>
 *   <li>{@link io.maestro.spring.annotation.MaestroSignalListener} — routes
 *       external Kafka events to workflow signals.</li>
 *   <li>{@link io.maestro.spring.annotation.SignalRouting} — return type for
 *       signal listener methods, specifying the target workflow and payload.</li>
 * </ul>
 */
@NullMarked
package io.maestro.spring.annotation;

import org.jspecify.annotations.NullMarked;
