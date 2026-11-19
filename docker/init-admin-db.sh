#!/bin/bash
# Creates the maestro_admin database for the admin dashboard, and the
# payment_gateway database for sample-payment-gateway: services never share
# Maestro tables (docs/support-matrix.md), so the payment gateway does not use
# the order service's "maestro" database.
# Mounted into postgres container's docker-entrypoint-initdb.d/.
# Only runs on first initialization (fresh volume).

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE maestro_admin;
    GRANT ALL PRIVILEGES ON DATABASE maestro_admin TO $POSTGRES_USER;
    CREATE DATABASE payment_gateway;
    GRANT ALL PRIVILEGES ON DATABASE payment_gateway TO $POSTGRES_USER;
EOSQL
