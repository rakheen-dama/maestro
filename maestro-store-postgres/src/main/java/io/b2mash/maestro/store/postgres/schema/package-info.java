/**
 * Maestro-owned schema management: {@link io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer}
 * applies the SQL migrations shipped by every Maestro Postgres module on the
 * classpath, recorded in Maestro's own Flyway history table so that they never
 * collide with the host application's migrations.
 */
@NullMarked
package io.b2mash.maestro.store.postgres.schema;

import org.jspecify.annotations.NullMarked;
