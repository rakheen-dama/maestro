#!/usr/bin/env bash
# jvm-env.sh — the environment and JVM options every Maestro demo JVM shares.
#
# Sourced by start-services.sh (all four/five JVMs) and restart-loan-app.sh
# (the single JVM scenario 2 restarts after `kill -9`). It is a library: it
# defines and exports, it never starts anything.
#
# WHY THIS FILE EXISTS. These settings used to be duplicated verbatim in both
# scripts, with a comment in each asking the next editor to keep them in step.
# That is not a safeguard — `maestro.recovery.poll-interval` reached
# restart-loan-app.sh precisely because scenario 2's restarted JVM needs it, and
# had it been added to only one of the two the demo would not have errored, it
# would have gone quietly back to a 250-second pause on its most important beat
# (demo/.evidence/task-4-fix-f1-scenario-2-phase-timings.log). A slow demo is a
# far worse failure than a loud one. One definition makes the skew impossible.
#
# Provides:
#   exported env vars   — where the demo stack lives, plus two behaviour flags
#   MAESTRO_JVM_OPTS[]  — the java options array, expand as "${MAESTRO_JVM_OPTS[@]}"
#
# Deliberately NOT here: anything instance-specific (SERVER_PORT, POSTGRES_DB,
# the jar path). Those belong to whoever is starting a particular JVM.

# ── Where the demo stack lives (must match demo/docker-compose.yml) ──────
export POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
export POSTGRES_PORT="${POSTGRES_PORT:-5433}"
export POSTGRES_USER="${POSTGRES_USER:-maestro}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-maestro}"
export VALKEY_HOST="${VALKEY_HOST:-localhost}"
export VALKEY_PORT="${VALKEY_PORT:-6380}"
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:29093}"

# Jaeger's OTLP/HTTP receiver. Bound in every service's application.yml to
# Spring Boot 4's `management.opentelemetry.tracing.export.otlp.endpoint`.
# The Boot 3.x property name (`management.otlp.tracing.endpoint`) is read by
# NOTHING on Boot 4 — the exporter bean is never created and no connection is
# ever attempted, which looks exactly like "Jaeger is empty" (task-1-report §3).
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318/v1/traces}"

# The loan sample sets maestro.admin.events.enabled=false in application.yml
# (it runs no dashboard). This demo DOES run maestro-admin, so lifecycle
# publishing is turned back on here via relaxed binding rather than by editing
# the sample's committed config.
export MAESTRO_ADMIN_EVENTS_ENABLED="${MAESTRO_ADMIN_EVENTS_ENABLED:-true}"

# ── JVM options ──────────────────────────────────────────────────────────

# `maestro.activity.duration` is a plain Micrometer Timer: by default it
# publishes only _count/_sum/_max, which cannot answer "p95 by activity". This
# asks Micrometer for a percentile HISTOGRAM for that one meter, which is what
# emits the `maestro_activity_duration_seconds_bucket{le=...}` series the
# dashboard's histogram_quantile() panels read. Scoped to the single meter name
# so no other timer's cardinality changes.
_activity_histogram_prop="-Dmanagement.metrics.distribution.percentiles-histogram.maestro.activity.duration=true"

# HOW FAST RECOVERY NOTICES A DEAD NODE.
#
# `maestro.recovery.poll-interval` defaults to 60s (MaestroProperties
# RecoveryProperties). That is a production-sane default — it bounds how often
# every node scans for workflows whose owner's instance lock has expired — but
# it is not a watchable one. Scenario 2 (kill -9 + restart) crosses that poll
# more than once: the restarted node must adopt the parked workflow before it
# can consume `underwriting.decision`, and again before the rate lock. At the
# 60s default the demo's headline beat measured 250s of silence; at 5s the same
# phase measures 40s (demo/.evidence/task-4-fix-f1-scenario-2-after-poll-interval.log).
#
# 5s is a DEMO tuning knob, not a fix: recovery is as fast as you configure it
# to notice. Set as a system property rather than in the samples'
# application.yml on purpose — the loan e2e suite runs against the committed
# config, and the committed default should stay production-sane.
_recovery_poll_prop="-Dmaestro.recovery.poll-interval=${DEMO_RECOVERY_POLL_INTERVAL:-5s}"

# WHAT MAKES THE CROSS-SERVICE TRACE ONE TRACE.
#
# Maestro's own KafkaTracePropagation injects/extracts W3C `traceparent` on the
# `maestro.tasks.*` / `maestro.signals.*` topics it owns. But the three loan
# services talk to each other over the SAMPLE's OWN domain topics —
# loans.verification.{requests,results}, loans.underwriting.{requests,decisions}
# — published with a plain Spring `KafkaTemplate` and consumed with plain
# `@KafkaListener` (KafkaLoanMessagingActivities, UnderwritingRequestListener,
# VerificationRequestListener). Maestro never sees those records, so the header
# has to come from Spring Kafka's own observation instrumentation.
#
# Both flags default to FALSE in Spring Boot 4.0.5 (verified against
# spring-boot-kafka-4.0.5's spring-configuration-metadata.json). With them off,
# records go out with NO_HEADERS, each service starts a fresh root trace, and
# Jaeger shows three unrelated single-service traces that look superficially
# fine — which is exactly what this demo must not show.
#
#   listener.observation-enabled  → consumer side: reads `traceparent` back and
#                                   continues the trace instead of starting a
#                                   new one. THIS FLAG IS THE ONE DOING WORK —
#                                   Boot's listener-container factory is not
#                                   shadowed, so the property reaches it.
#
#   template.observation-enabled  → INERT. It configures Boot's auto-configured
#                                   `kafkaTemplate`, which never exists in a
#                                   Maestro+Kafka app: Boot declares it
#                                   @ConditionalOnMissingBean(KafkaTemplate.class)
#                                   and Maestro contributes maestroKafkaTemplate
#                                   unconditionally (Issue 23; evidence in
#                                   demo/.evidence/task-2-kafka-template-library-defect.log).
#                                   Kept only so the pair reads symmetrically.
#
# THE PRODUCER SIDE IS A BEAN, NOT THIS FLAG. Each of the three services
# declares ObservedKafkaTemplateConfig in src/main/java/.../config/ — a
# @Bean NAMED maestroKafkaTemplate that calls setObservationEnabled(true) and
# wins because Maestro's own is @ConditionalOnMissingBean(name =
# "maestroKafkaTemplate"). DELETING THAT CLASS BREAKS THE CROSS-SERVICE TRACE
# INTO THREE UNRELATED ONES, and no flag here will save you.
_kafka_observation_props=(
    -Dspring.kafka.template.observation-enabled=true
    -Dspring.kafka.listener.observation-enabled=true
)

# Every JVM is capped at 256m of heap: four of these plus seven containers has
# to fit on a laptop.
MAESTRO_JVM_OPTS=(
    -Xmx256m
    -XX:+UseSerialGC
    "$_activity_histogram_prop"
    "$_recovery_poll_prop"
    "${_kafka_observation_props[@]}"
)
