#!/usr/bin/env bash
# preflight.sh — T-30 cold start. Run this from a cold machine, once, and do not
# touch anything until it prints PREFLIGHT PASSED.
#
#   demo/scripts/preflight.sh
#
# Steps, in order, any failure exits non-zero:
#   0  host tools present
#   1  every demo port is free            (before anything is started)
#   2  pull container images
#   3  build all jars, including loan-application-v2.jar
#   4  docker compose up -d, wait for every container healthy
#   5  verify all 11 Kafka topics exist   (they are never auto-created)
#   6  start the four host JVMs
#   7  drive one throwaway loan end to end
#   8  print PID + build fingerprint of every running service
#
# Env: DEMO_SKIP_PULL=1 skips step 2, DEMO_SKIP_BUILD=1 skips step 3.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/.." && pwd)"
COMPOSE=("docker" "compose" "-f" "$DEMO_DIR/docker-compose.yml")
PG_CONTAINER="${PG_CONTAINER:-maestro-demo-postgres-1}"

# 3000 Grafana, 4318 Jaeger OTLP/HTTP, 5433 Postgres, 6380 Valkey, 8080 admin,
# 8091/8092/8093 loan services, 9090 Prometheus, 16686 Jaeger UI, 29093 Kafka.
PORTS=(3000 4318 5433 6380 8080 8091 8092 8093 9090 16686 29093)

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[32mok\033[0m   %s\n' "$*"; }
info() { printf '         %s\n' "$*"; }
die()  { printf '\n\033[31mPREFLIGHT FAILED: %s\033[0m\n' "$*" >&2; exit 1; }

START=$(date +%s)

step "0/8 host tools"
for t in docker curl jq lsof nc shasum unzip; do
  command -v "$t" >/dev/null 2>&1 || die "missing host tool: $t"
done
docker info >/dev/null 2>&1 || die "Docker is not running"
ok "docker, curl, jq, lsof, nc, shasum, unzip"

step "1/8 every demo port free"
busy=()
for p in "${PORTS[@]}"; do
  lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && busy+=("$p")
done
if [ ${#busy[@]} -gt 0 ]; then
  for p in "${busy[@]}"; do lsof -nP -iTCP:"$p" -sTCP:LISTEN | tail -n +2; done
  die "ports already in use: ${busy[*]}
       Run: demo/scripts/stop-services.sh && ${COMPOSE[*]} down
       If the loan sample's own compose stack is up, it collides on 5433/6380/29093 — take it down first."
fi
ok "${#PORTS[@]} ports free: ${PORTS[*]}"

step "2/8 pull images"
if [ "${DEMO_SKIP_PULL:-0}" = "1" ]; then info "skipped (DEMO_SKIP_PULL=1)"
else "${COMPOSE[@]}" pull --quiet || die "image pull failed"; ok "images present"; fi

step "3/8 build jars (including v2)"
if [ "${DEMO_SKIP_BUILD:-0}" = "1" ]; then info "skipped (DEMO_SKIP_BUILD=1)"
else
  ( cd "$REPO_ROOT" && ./gradlew --quiet -x test \
      :maestro-samples:sample-loan-origination:loan-application-service:bootJar \
      :maestro-samples:sample-loan-origination:loan-application-service:v2BootJar \
      :maestro-samples:sample-loan-origination:verification-gateway-service:bootJar \
      :maestro-samples:sample-loan-origination:underwriting-service:bootJar \
      :maestro-admin:bootJar ) || die "gradle build failed"
  ok "bootJar + v2BootJar"
fi
V2_JAR="$REPO_ROOT/maestro-samples/sample-loan-origination/loan-application-service/build/libs/loan-application-v2.jar"
[ -f "$V2_JAR" ] || die "loan-application-v2.jar missing — the v1->v2 deep dive (D1) cannot run without it"
ok "loan-application-v2.jar present"

step "4/8 containers up and healthy"
"${COMPOSE[@]}" up -d || die "docker compose up failed"
deadline=$(( $(date +%s) + 180 ))
for c in postgres valkey kafka prometheus grafana; do
  while :; do
    cid=$("${COMPOSE[@]}" ps -q "$c" 2>/dev/null || true)
    [ -n "$cid" ] || die "container $c did not start"
    st=$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo unknown)
    [ "$st" = "healthy" ] && { ok "$c healthy"; break; }
    [ "$(date +%s)" -lt "$deadline" ] || die "$c is '$st' after 180s — ${COMPOSE[*]} logs $c"
    sleep 2
  done
done
# Jaeger declares no healthcheck; probe it directly.
for probe in "http://localhost:16686/" "http://localhost:4318/v1/traces"; do
  while :; do
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 2 "$probe" || echo 000)
    [ "$code" != "000" ] && { ok "jaeger $probe answers ($code)"; break; }
    [ "$(date +%s)" -lt "$deadline" ] || die "jaeger not answering on $probe"
    sleep 2
  done
done
init_exit=$(docker inspect -f '{{.State.ExitCode}}' "$("${COMPOSE[@]}" ps -aq kafka-init)" 2>/dev/null || echo 1)
[ "$init_exit" = "0" ] || die "kafka-init exited $init_exit — ${COMPOSE[*]} logs kafka-init"
ok "kafka-init exited 0"

step "5/8 Kafka topics (never auto-created)"
want=$(grep -oE '\-\-topic [a-zA-Z0-9._-]+' "$DEMO_DIR/docker-compose.yml" | awk '{print $2}' | sort -u)
deadline=$(( $(date +%s) + 120 ))
while :; do
  have=$("${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
           --bootstrap-server localhost:9092 --list 2>/dev/null | sort -u || true)
  missing=$(comm -23 <(echo "$want") <(echo "$have") | tr '\n' ' ')
  [ -z "${missing// /}" ] && { ok "$(echo "$want" | wc -l | tr -d ' ') topics present"; break; }
  [ "$(date +%s)" -lt "$deadline" ] || die "topics still missing after 120s: $missing"
  sleep 3
done

step "6/8 start host JVMs"
DEMO_SKIP_BUILD=1 "$DEMO_DIR/scripts/start-services.sh" || die "start-services.sh failed — see $DEMO_DIR/.run/*.log"
ok "loan-application 8091, verification-gateway 8092, underwriting 8093, admin 8080"

step "7/8 throwaway loan end to end"
THROWAWAY="preflight-$(date +%s)"
"$DEMO_DIR/scripts/drive-loan.sh" happy "$THROWAWAY" >/dev/null 2>&1 \
  || die "the throwaway loan did not complete. Re-run by hand to see why:
       demo/scripts/drive-loan.sh happy $THROWAWAY"
st=$(curl -sS "http://localhost:8091/applications/$THROWAWAY" | jq -r '.status')
[ "$st" = "COMPLETED" ] || die "throwaway loan $THROWAWAY is $st, expected COMPLETED"
ok "$THROWAWAY COMPLETED (path is warm: JIT, connection pools, Kafka consumer groups)"
info "clear it before the demo with: demo/scripts/reset.sh"

step "8/8 process identity — trust nothing on screen until this matches"
printf '    %-26s %-8s %-14s %s\n' SERVICE PID "JAR-SHA256(12)" JAR
for name in loan-application-service verification-gateway-service underwriting-service maestro-admin; do
  pidfile="$DEMO_DIR/.run/$name.pid"
  [ -f "$pidfile" ] || die "no pid file for $name"
  pid=$(cat "$pidfile")
  kill -0 "$pid" 2>/dev/null || die "$name pid $pid is not alive"
  jar=$(ps -o command= -p "$pid" | tr ' ' '\n' | grep -E '\.jar$' | tail -1)
  [ -f "$jar" ] || die "cannot resolve the jar $name (pid $pid) is actually running"
  sha=$(shasum -a 256 "$jar" | cut -c1-12)
  printf '    %-26s %-8s %-14s %s\n' "$name" "$pid" "$sha" "$(basename "$jar")"
done
started=$(ps -o lstart= -p "$(cat "$DEMO_DIR/.run/loan-application-service.pid")")
info "loan-application started at:$started  (if that is not from this minute, you are looking at a stale JVM)"

printf '\n\033[32mPREFLIGHT PASSED in %ss.\033[0m Now run: demo/scripts/reset.sh\n' "$(( $(date +%s) - START ))"
