-- A host application's own first migration, at Spring Boot's default Flyway
-- location. Used only by PostgresStoreAutoConfigurationHostFlywayTest; Maestro's
-- migrations live under classpath:db/maestro/** and never share this version.
CREATE TABLE host_customer (id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL);
