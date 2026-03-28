# Multi-stage build for Maestro sample applications.
# Usage:
#   docker build --target order-service -t maestro-order-service .
#   docker build --target payment-gateway -t maestro-payment-gateway .
# Or via docker-compose (recommended):
#   docker-compose up --build

# ── Stage 1: Build all modules ─────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build-logic build-logic
COPY settings.gradle.kts .
COPY gradle.properties .
COPY maestro-core maestro-core
COPY maestro-spring-boot-starter maestro-spring-boot-starter
COPY maestro-store-jdbc maestro-store-jdbc
COPY maestro-store-postgres maestro-store-postgres
COPY maestro-messaging-kafka maestro-messaging-kafka
COPY maestro-lock-valkey maestro-lock-valkey
COPY maestro-test maestro-test
COPY maestro-admin maestro-admin
COPY maestro-admin-client maestro-admin-client
COPY maestro-samples maestro-samples
RUN chmod +x gradlew && \
    ./gradlew :maestro-samples:sample-order-service:bootJar \
              :maestro-samples:sample-payment-gateway:bootJar \
              --no-daemon --parallel -x test

# ── Stage 2a: Order Service ────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS order-service
WORKDIR /app
COPY --from=builder /app/maestro-samples/sample-order-service/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

# ── Stage 2b: Payment Gateway ─────────────────────────────────────────
FROM eclipse-temurin:25-jre AS payment-gateway
WORKDIR /app
COPY --from=builder /app/maestro-samples/sample-payment-gateway/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
