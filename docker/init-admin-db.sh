#!/bin/bash
# Creates the maestro_admin database for the admin dashboard.
# Mounted into postgres container's docker-entrypoint-initdb.d/.
# Only runs on first initialization (fresh volume).

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE maestro_admin;
    GRANT ALL PRIVILEGES ON DATABASE maestro_admin TO $POSTGRES_USER;
EOSQL
