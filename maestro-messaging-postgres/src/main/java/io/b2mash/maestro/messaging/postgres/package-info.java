/**
 * PostgreSQL-based implementation of the Maestro {@link io.b2mash.maestro.core.spi.WorkflowMessaging}
 * and {@link io.b2mash.maestro.core.spi.SignalNotifier} SPIs.
 *
 * <p>Provides task dispatch, cross-service signal delivery, lifecycle event
 * publishing, and real-time signal notification using PostgreSQL tables with
 * polling and {@code LISTEN/NOTIFY} for immediate wake-up.
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
