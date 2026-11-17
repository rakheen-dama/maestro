/**
 * Maestro Admin Client — {@link io.b2mash.maestro.admin.client.AdminEventPublisher},
 * a plain lifecycle event publisher.
 *
 * <p>This module contributes no auto-configuration. A Maestro service
 * publishes its lifecycle events through the engine's {@code WorkflowMessaging}
 * ({@code maestro-messaging-kafka}) and needs no dependency on this module.
 */
@NullMarked
package io.b2mash.maestro.admin.client;

import org.jspecify.annotations.NullMarked;
