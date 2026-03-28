/**
 * Processing infrastructure for {@link io.maestro.spring.annotation.MaestroSignalListener}
 * annotations.
 *
 * <p>Contains the bean post-processor that discovers annotated methods and
 * creates Kafka consumer containers to route external events into workflow
 * signals.
 */
@NullMarked
package io.maestro.messaging.kafka.listener;

import org.jspecify.annotations.NullMarked;
