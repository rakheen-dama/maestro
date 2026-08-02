#!/bin/bash
# Chaos harness Postgres init (chaos-harness-design.md §2). Runs once during
# container initialisation on the shared Postgres instance: creates one database
# per loan-origination service (mirroring docker/init-loan-dbs.sh) and enables
# pg_stat_statements in each so the metrics sampler can read recovery-query and
# lock-probe rates (§6).
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE loan_application;
    CREATE DATABASE verification_gateway;
    CREATE DATABASE underwriting;
EOSQL

for db in loan_application verification_gateway underwriting maestro; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" \
        -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
done
