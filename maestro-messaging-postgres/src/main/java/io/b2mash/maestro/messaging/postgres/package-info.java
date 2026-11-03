/**
 * PostgreSQL-based implementation of the Maestro {@link io.b2mash.maestro.core.spi.WorkflowMessaging}
 * and {@link io.b2mash.maestro.core.spi.SignalNotifier} SPIs.
 *
 * <p>Provides cross-service signal delivery and real-time signal notification
 * using a PostgreSQL table with polling and {@code LISTEN/NOTIFY} for immediate
 * wake-up. Lifecycle events are Kafka-only: on this transport
 * {@code publishLifecycleEvent} is a no-op that logs one WARN.
 *
 * <p>This module is an alternative to the Kafka-based messaging module for
 * deployments that want to avoid an external message broker dependency.
 *
 * @see io.b2mash.maestro.messaging.postgres.PostgresWorkflowMessaging
 * @see io.b2mash.maestro.messaging.postgres.PostgresSignalNotifier
 * @see io.b2mash.maestro.messaging.postgres.config.PostgresMessagingAutoConfiguration
 */
@NullMarked
package io.b2mash.maestro.messaging.postgres;

import org.jspecify.annotations.NullMarked;
