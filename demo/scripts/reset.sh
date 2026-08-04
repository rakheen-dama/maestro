#!/usr/bin/env bash
# reset.sh — back to a clean slate between rehearsals, in under 30 seconds.
#
#   demo/scripts/reset.sh
#
# What it does:
#   1. stops the four host JVMs (fast SIGTERM, GRACE=5)
#   2. truncates the Maestro tables in loan_application / verification_gateway /
#      underwriting, and clears the maestro_admin database
#   3. flushes Valkey (locks + leader keys)
#   4. restarts the JVMs from the *v1* jars — which is also how you come back
#      from the v1->v2 move
#
# What it deliberately does NOT do:
#   - touch containers (Postgres, Kafka, Valkey, Prometheus, Grafana, Jaeger
#     all stay up; Grafana history and Jaeger traces survive on purpose)
#   - rebuild jars (DEMO_SKIP_BUILD=1)
#
# Idempotent: safe to run when nothing is running, or twice in a row.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-maestro-demo-postgres-1}"
VALKEY_CONTAINER="${VALKEY_CONTAINER:-maestro-demo-valkey-1}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mreset failed: %s\033[0m\n' "$*" >&2; exit 1; }

START=$(date +%s)

docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" >/dev/null 2>&1 \
  || die "$PG_CONTAINER is not running. Bring the stack up first: docker compose -f demo/docker-compose.yml up -d"

say "1/4 stopping host JVMs"
GRACE=5 "$DEMO_DIR/scripts/stop-services.sh" || true
# The v1->v2 move parks its own log alongside; drop it so a stale v2 log can
# never be mistaken for the live one.
rm -f "$DEMO_DIR/.run/loan-application-service-v2.log" \
      "$DEMO_DIR/.run/crash-"* 2>/dev/null || true

say "2/4 truncating Maestro tables"
for db in loan_application verification_gateway underwriting; do
  docker exec "$PG_CONTAINER" psql -qtA -U maestro -d "$db" -c "
    TRUNCATE TABLE maestro_workflow_event,
                   maestro_workflow_signal,
                   maestro_workflow_timer,
                   maestro_workflow_instance
            RESTART IDENTITY CASCADE;
    TRUNCATE TABLE maestro_distributed_lock, maestro_leader_election;" >/dev/null \
    || die "could not truncate $db"
  printf '    %-22s cleared\n' "$db"
done
docker exec "$PG_CONTAINER" psql -qtA -U maestro -d maestro_admin -c "
  TRUNCATE TABLE admin_event, admin_metrics, admin_workflow, admin_service RESTART IDENTITY CASCADE;" >/dev/null \
  || die "could not clear maestro_admin"
printf '    %-22s cleared\n' "maestro_admin"

say "3/4 flushing Valkey"
docker exec "$VALKEY_CONTAINER" valkey-cli FLUSHALL >/dev/null || die "could not flush Valkey"

say "4/4 restarting host JVMs from the v1 jars"
DEMO_SKIP_BUILD=1 "$DEMO_DIR/scripts/start-services.sh" >/dev/null || die "start-services.sh failed — see $DEMO_DIR/.run/*.log"

for port in 8091 8092 8093 8080; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$port/actuator/health" || echo 000)
  [ "$code" = "200" ] || die "port $port health is $code after restart"
  printf '    localhost:%-6s health 200\n' "$port"
done

rows=$(docker exec "$PG_CONTAINER" psql -qtA -U maestro -d loan_application \
        -c "select count(*) from maestro_workflow_instance" | tr -d '[:space:]')
[ "$rows" = "0" ] || die "loan_application still has $rows workflow instances"

say "clean slate in $(( $(date +%s) - START ))s — 0 workflow instances, all four JVMs healthy"
