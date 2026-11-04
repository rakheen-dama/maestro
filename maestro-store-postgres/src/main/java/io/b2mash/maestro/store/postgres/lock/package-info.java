/**
 * PostgreSQL-based {@link io.b2mash.maestro.core.spi.DistributedLock}
 * implementation using advisory-style row locks, plus the periodic cleaner of
 * expired lock rows. Ships with the store so one dependency gives store, lock
 * and signal wake.
 */
@NullMarked
package io.b2mash.maestro.store.postgres.lock;

import org.jspecify.annotations.NullMarked;
