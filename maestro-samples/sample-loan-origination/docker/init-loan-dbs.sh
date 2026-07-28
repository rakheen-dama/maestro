#!/bin/bash
# Creates one database per loan-origination service on the shared Postgres
# instance. Mounted into the postgres container's docker-entrypoint-initdb.d/.
# Only runs on first initialization (fresh volume).

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE loan_application;
    GRANT ALL PRIVILEGES ON DATABASE loan_application TO $POSTGRES_USER;
    CREATE DATABASE verification_gateway;
    GRANT ALL PRIVILEGES ON DATABASE verification_gateway TO $POSTGRES_USER;
    CREATE DATABASE underwriting;
    GRANT ALL PRIVILEGES ON DATABASE underwriting TO $POSTGRES_USER;
EOSQL
