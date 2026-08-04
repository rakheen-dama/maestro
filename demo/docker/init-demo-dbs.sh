#!/bin/bash
# Creates one database per demo process on the shared Postgres instance:
# the three loan-origination services plus the maestro-admin dashboard.
# Mounted into the postgres container's docker-entrypoint-initdb.d/.
# Only runs on first initialization (fresh volume) — `docker compose down -v`
# is what re-runs it.
#
# Modelled on maestro-samples/sample-loan-origination/docker/init-loan-dbs.sh
# and docker/init-admin-db.sh, merged so the demo needs exactly one Postgres.

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE loan_application;
    GRANT ALL PRIVILEGES ON DATABASE loan_application TO $POSTGRES_USER;
    CREATE DATABASE verification_gateway;
    GRANT ALL PRIVILEGES ON DATABASE verification_gateway TO $POSTGRES_USER;
    CREATE DATABASE underwriting;
    GRANT ALL PRIVILEGES ON DATABASE underwriting TO $POSTGRES_USER;
    CREATE DATABASE maestro_admin;
    GRANT ALL PRIVILEGES ON DATABASE maestro_admin TO $POSTGRES_USER;
EOSQL
