/**
 * Processing infrastructure for {@link io.b2mash.maestro.spring.annotation.MaestroSignalListener}
 * annotations.
 *
 * <p>Contains the bean post-processor that discovers annotated methods and
 * creates Kafka consumer containers to route external events into workflow
 * signals.
 */
@NullMarked
package io.b2mash.maestro.messaging.kafka.listener;

import org.jspecify.annotations.NullMarked;
