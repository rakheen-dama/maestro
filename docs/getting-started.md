# Getting Started

Build a crash-recoverable workflow in under 30 minutes. This tutorial takes you from an empty Spring Boot 4 application to a running durable workflow with activities, signals, queries, and tests.

[← Back to README](../README.md)

---

## Prerequisites

| Requirement | Why |
|---|---|
| **Java 21+** | Virtual threads power the workflow engine |
| **Docker** | Runs Postgres, Kafka, and Valkey locally |
| **Gradle 9** | Or use the Gradle wrapper (`./gradlew`) |

---

## Step 1: Create a Spring Boot 4 Project

Generate a project at [start.spring.io](https://start.spring.io) (Spring Boot 4.x, Java 21) or create the following `build.gradle.kts` manually:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Maestro
    implementation("io.maestro:maestro-spring-boot-starter")
    implementation("io.maestro:maestro-store-postgres")
    implementation("io.maestro:maestro-messaging-kafka")
    implementation("io.maestro:maestro-lock-valkey")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Test
    testImplementation("io.maestro:maestro-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

Create your main application class:

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

---

## Step 2: Start Infrastructure

Create a `docker-compose.yml` in your project root:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: maestro
      POSTGRES_USER: maestro
      POSTGRES_PASSWORD: maestro
    ports:
      - "5432:5432"

  valkey:
    image: valkey/valkey:8-alpine
    ports:
      - "6379:6379"

  kafka:
    image: apache/kafka:3.9.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,EXTERNAL://0.0.0.0:29092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: "my-app-cluster-001"
    ports:
      - "29092:29092"
```

Start the containers:

```bash
docker-compose up -d
```

Maestro never auto-creates Kafka topics. Create them manually:

```bash
# Find your Kafka container name
KAFKA_CONTAINER=$(docker ps --format '{{.Names}}' | grep kafka)

# Task queue topic
docker exec -it $KAFKA_CONTAINER /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic maestro.tasks.default --partitions 3

# Signal topic (one per service)
docker exec -it $KAFKA_CONTAINER /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic maestro.signals.my-service --partitions 3

# Admin events topic (optional, for the dashboard)
docker exec -it $KAFKA_CONTAINER /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic maestro.admin.events --partitions 1
```

---

## Step 3: Configure Maestro

Create `src/main/resources/application.yml`:

```yaml
maestro:
  service-name: my-service
  store:
    type: postgres
  messaging:
    type: kafka
    topics:
      tasks: maestro.tasks.default
      signals: maestro.signals.my-service
  lock:
    type: valkey
  worker:
    task-queues:
      - name: default
        concurrency: 10

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/maestro
    username: maestro
    password: maestro
  kafka:
    bootstrap-servers: localhost:29092
  data:
    redis:
      host: localhost
      port: 6379
```

Maestro uses Flyway to create its `maestro_*` tables automatically on startup. No manual DDL required.

---

## Step 4: Define an Activity

Activities are the building blocks of a workflow. Each activity method call is intercepted, executed, and its result persisted to Postgres. On replay (after a crash), stored results are returned instantly without re-executing the method.

Create the activity interface:

```java
package com.example.demo.activity;

import io.maestro.core.annotation.Activity;

@Activity
public interface GreetingActivities {

    String generateGreeting(String name);

    void sendEmail(String to, String body);
}
```

Create the implementation as a regular Spring bean:

```java
package com.example.demo.activity;

import org.springframework.stereotype.Service;

@Service
public class GreetingActivitiesImpl implements GreetingActivities {

    @Override
    public String generateGreeting(String name) {
        return "Hello, " + name + "! Welcome aboard.";
    }

    @Override
    public void sendEmail(String to, String body) {
        // In production, call an email API here
        System.out.println("Sending email to " + to + ": " + body);
    }
}
```

Key points:
- `@Activity` goes on the **interface**, not the implementation.
- The implementation is a plain Spring `@Service` bean. It can inject other Spring beans, call HTTP APIs, write to databases -- anything a normal service does.
- Maestro creates a memoizing proxy around the implementation that intercepts every call, checks Postgres for stored results, and persists new results.

---

## Step 5: Create a Workflow

```java
package com.example.demo.workflow;

import com.example.demo.activity.GreetingActivities;
import io.maestro.core.annotation.ActivityStub;
import io.maestro.core.annotation.DurableWorkflow;
import io.maestro.core.annotation.RetryPolicy;
import io.maestro.core.annotation.WorkflowMethod;
import io.maestro.core.context.WorkflowContext;

import java.time.Duration;

@DurableWorkflow(name = "welcome", taskQueue = "default")
public class WelcomeWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private GreetingActivities greetings;

    @WorkflowMethod
    public String welcome(String customerName) {
        var workflow = WorkflowContext.current();

        // Step 1: Generate greeting (memoized -- survives restarts)
        String greeting = greetings.generateGreeting(customerName);

        // Step 2: Durable sleep (creates a timer in Postgres, not Thread.sleep)
        workflow.sleep(Duration.ofSeconds(5));

        // Step 3: Send email (memoized)
        greetings.sendEmail(customerName + "@example.com", greeting);

        return greeting;
    }
}
```

What each annotation does:

| Annotation | Purpose |
|---|---|
| `@DurableWorkflow` | Marks this class as a crash-recoverable workflow. The `name` is used as the workflow type identifier. The `taskQueue` routes to worker groups. |
| `@WorkflowMethod` | Marks the single entry-point method. Accepts zero or one parameter (the workflow input). The return value is serialized as the workflow output. |
| `@ActivityStub` | Tells Maestro to inject a memoizing proxy for this activity interface. `startToCloseTimeout` is an ISO 8601 duration string. |
| `@RetryPolicy` | Configures exponential-backoff retry for failed activity calls. Defaults: 3 max attempts, 1s initial interval, 2x backoff. |

The `workflow.sleep()` call creates a durable timer in Postgres. If the process crashes during the sleep, the timer survives. On restart, Maestro replays completed steps instantly and resumes from the timer.

---

## Step 6: Add a REST Endpoint

```java
package com.example.demo.controller;

import com.example.demo.workflow.WelcomeWorkflow;
import io.maestro.spring.client.MaestroClient;
import io.maestro.spring.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class WelcomeController {

    private final MaestroClient maestro;

    public WelcomeController(MaestroClient maestro) {
        this.maestro = maestro;
    }

    @PostMapping("/welcome/{name}")
    public ResponseEntity<Map<String, String>> welcome(@PathVariable String name) {
        var options = WorkflowOptions.builder()
            .workflowId("welcome-" + name)
            .build();

        UUID instanceId = maestro.newWorkflow(WelcomeWorkflow.class, options)
            .startAsync(name);

        return ResponseEntity.accepted()
            .body(Map.of("workflowId", "welcome-" + name,
                          "instanceId", instanceId.toString()));
    }
}
```

`MaestroClient` is auto-configured by the starter. Inject it wherever you need to start workflows, send signals, or query state.

The `startAsync` method persists the workflow instance to Postgres and enqueues execution on Kafka. It returns immediately with the instance UUID.

---

## Step 7: Run It

```bash
./gradlew bootRun
```

Test with curl:

```bash
curl -X POST http://localhost:8080/welcome/Alice
```

Response:

```json
{"workflowId":"welcome-Alice","instanceId":"550e8400-e29b-41d4-a716-446655440000"}
```

Watch the logs. You will see:
1. `generateGreeting` executes and its result is persisted.
2. A 5-second durable timer is created.
3. After the timer fires, `sendEmail` executes.

**Try the crash-recovery guarantee:** Kill the app during the 5-second sleep (`Ctrl+C`), then restart with `./gradlew bootRun`. Maestro detects the in-progress workflow, replays the already-completed greeting step instantly (no re-execution), and resumes from the timer.

---

## Step 8: Add a Signal

Signals let external events drive workflow execution. Here, we extend the workflow to pause and wait for a manager's approval before sending the welcome email.

First, create a record for the approval decision:

```java
package com.example.demo.domain;

import org.jspecify.annotations.Nullable;

public record ApprovalDecision(boolean approved, @Nullable String reason) {}
```

Now create the workflow with signal handling and a query method:

```java
package com.example.demo.workflow;

import com.example.demo.activity.GreetingActivities;
import com.example.demo.domain.ApprovalDecision;
import io.maestro.core.annotation.ActivityStub;
import io.maestro.core.annotation.DurableWorkflow;
import io.maestro.core.annotation.QueryMethod;
import io.maestro.core.annotation.WorkflowMethod;
import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.SignalTimeoutException;

import java.time.Duration;

@DurableWorkflow(name = "welcome-with-approval", taskQueue = "default")
public class WelcomeWithApprovalWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S")
    private GreetingActivities greetings;

    private volatile String currentStep = "PENDING";

    @WorkflowMethod
    public String welcome(String customerName) {
        var workflow = WorkflowContext.current();

        currentStep = "AWAITING_APPROVAL";

        // Wait up to 24 hours for manager approval
        try {
            var approval = workflow.awaitSignal(
                "approval", ApprovalDecision.class, Duration.ofHours(24));

            if (!approval.approved()) {
                currentStep = "REJECTED";
                return "Rejected: " + approval.reason();
            }
        } catch (SignalTimeoutException e) {
            currentStep = "TIMED_OUT";
            return "Approval timed out";
        }

        currentStep = "SENDING_WELCOME";
        String greeting = greetings.generateGreeting(customerName);
        greetings.sendEmail(customerName + "@example.com", greeting);

        currentStep = "COMPLETED";
        return greeting;
    }

    @QueryMethod
    public String getStatus() {
        return currentStep;
    }
}
```

Key details:
- `workflow.awaitSignal(name, type, timeout)` parks the workflow's virtual thread until a matching signal arrives or the timeout elapses. The state is persisted -- the signal can arrive while the service is down.
- `SignalTimeoutException` is thrown if no signal arrives within the timeout.
- `@QueryMethod` exposes `getStatus()` for external callers. Query methods run on the caller's thread, so fields they read must be `volatile` or otherwise thread-safe.

Add endpoints for approval and status:

```java
package com.example.demo.controller;

import com.example.demo.domain.ApprovalDecision;
import com.example.demo.workflow.WelcomeWithApprovalWorkflow;
import io.maestro.spring.client.MaestroClient;
import io.maestro.spring.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class WelcomeApprovalController {

    private final MaestroClient maestro;

    public WelcomeApprovalController(MaestroClient maestro) {
        this.maestro = maestro;
    }

    @PostMapping("/approval-welcome/{name}")
    public ResponseEntity<Map<String, String>> welcome(@PathVariable String name) {
        var options = WorkflowOptions.builder()
            .workflowId("welcome-" + name)
            .build();

        UUID instanceId = maestro
            .newWorkflow(WelcomeWithApprovalWorkflow.class, options)
            .startAsync(name);

        return ResponseEntity.accepted()
            .body(Map.of("workflowId", "welcome-" + name,
                          "instanceId", instanceId.toString()));
    }

    @PostMapping("/approval-welcome/{name}/approve")
    public ResponseEntity<Void> approve(@PathVariable String name,
                                         @RequestBody ApprovalDecision decision) {
        maestro.getWorkflow("welcome-" + name)
            .signal("approval", decision);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/approval-welcome/{name}/status")
    public String status(@PathVariable String name) {
        return maestro.getWorkflow("welcome-" + name)
            .query("getStatus", String.class);
    }
}
```

Test the full flow:

```bash
# Start the workflow
curl -X POST http://localhost:8080/approval-welcome/Bob

# Check status -- the workflow is parked, waiting for approval
curl http://localhost:8080/approval-welcome/Bob/status
# -> "AWAITING_APPROVAL"

# Approve
curl -X POST http://localhost:8080/approval-welcome/Bob/approve \
  -H 'Content-Type: application/json' \
  -d '{"approved": true, "reason": null}'

# Check status again
curl http://localhost:8080/approval-welcome/Bob/status
# -> "COMPLETED"
```

Signals are persisted immediately on delivery. If the service is down when the approval arrives, the signal is stored in Postgres. When the service restarts, Maestro finds and delivers it. No signal is ever lost.

---

## Step 9: Write a Test

The `maestro-test` module provides `TestWorkflowEnvironment` -- an in-memory engine that requires no Postgres, Kafka, or Valkey. It includes a controllable clock for advancing through durable timers instantly.

```java
package com.example.demo.workflow;

import com.example.demo.activity.GreetingActivities;
import com.example.demo.activity.GreetingActivitiesImpl;
import io.maestro.test.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WelcomeWorkflowTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(GreetingActivities.class, new GreetingActivitiesImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void shouldCompleteWelcomeWorkflow() throws TimeoutException {
        var handle = env.startWorkflow(WelcomeWorkflow.class, "Alice");

        // Advance past the 5-second durable sleep
        env.advanceTime(Duration.ofSeconds(5));

        String result = handle.getResult(String.class, Duration.ofSeconds(5));
        assertEquals("Hello, Alice! Welcome aboard.", result);
    }
}
```

Key testing features:

| Method | Purpose |
|---|---|
| `TestWorkflowEnvironment.create()` | Creates an in-memory engine with no external dependencies |
| `env.registerActivities(Interface.class, impl)` | Registers an activity implementation (real or mock) |
| `env.startWorkflow(WorkflowClass.class, input)` | Starts a workflow, returns a `TestWorkflowHandle` |
| `env.advanceTime(duration)` | Advances the clock and fires all due timers |
| `handle.getResult(type, timeout)` | Blocks until the workflow completes, returns the result |
| `handle.signal(name, payload)` | Delivers a signal to the workflow under test |
| `handle.getStatus()` | Returns the current workflow status |

Here is a test for the signal-based approval workflow:

```java
@Test
void shouldWaitForApprovalSignal() throws TimeoutException {
    var handle = env.startWorkflow(WelcomeWithApprovalWorkflow.class, "Bob");

    // Deliver the approval signal — Maestro persists it immediately.
    // If the workflow hasn't reached awaitSignal() yet, the signal
    // waits in the store and is consumed when the await is reached.
    handle.signal("approval", new ApprovalDecision(true, null));

    String result = handle.getResult(String.class, Duration.ofSeconds(5));
    assertEquals("Hello, Bob! Welcome aboard.", result);
}

@Test
void shouldRejectWhenNotApproved() throws TimeoutException {
    var handle = env.startWorkflow(WelcomeWithApprovalWorkflow.class, "Carol");

    // Signal is persisted and delivered when the workflow reaches awaitSignal()
    handle.signal("approval", new ApprovalDecision(false, "Not ready"));

    String result = handle.getResult(String.class, Duration.ofSeconds(5));
    assertEquals("Rejected: Not ready", result);
}
```

---

## Next Steps

You now have a working durable workflow with activities, signals, queries, and tests. Here is where to go from here:

- [Core Concepts](concepts.md) -- Understand all Maestro primitives: parallel branches, saga compensation, `retryUntil`, `collectSignals`
- [Configuration Reference](configuration.md) -- All `maestro.*` properties explained
- [Self-Recovery](self-recovery.md) -- How workflows survive crashes and how signals are never lost
- [Cross-Service Patterns](cross-service.md) -- Multi-service coordination with Kafka events and `@MaestroSignalListener`
- [Testing Guide](testing.md) -- Advanced testing: mocking activities, pre-delivering signals, asserting event logs
- [Sample Applications](../maestro-samples/README.md) -- Full e-commerce order fulfilment demo with payment gateway integration
