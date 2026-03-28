# Contributing to Maestro

[← Back to README](README.md)

Thank you for your interest in contributing to Maestro! Whether you are reporting a bug, proposing a feature, improving documentation, writing tests, or submitting code, your contributions are welcome and appreciated.

Maestro is an embeddable durable workflow engine delivered as a Spring Boot Starter. It provides Temporal.io-grade workflow durability without a central server, using infrastructure teams already operate: Postgres, Kafka, and Valkey/Redis.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Getting the Code](#getting-the-code)
- [Building](#building)
- [Running Tests](#running-tests)
- [Code Standards](#code-standards)
- [Architecture Rules](#architecture-rules)
- [Making Changes](#making-changes)
- [Pull Request Process](#pull-request-process)
- [Reporting Issues](#reporting-issues)
- [License](#license)

---

## Prerequisites

Before you begin, ensure you have the following installed:

| Tool | Version | Notes |
|---|---|---|
| **Java** | 25 or later | Virtual threads and scoped values required. Verify with `java --version`. |
| **Gradle** | 9.x | Provided via the Gradle wrapper (`./gradlew`). Do not install separately. |
| **Docker** | Latest stable | Required for Testcontainers-based integration tests. |
| **IDE** | IntelliJ IDEA recommended | Community or Ultimate. Enable annotation processing for JSpecify. |
| **Git** | Latest stable | |

---

## Getting the Code

1. **Fork** the repository on GitHub.

2. **Clone** your fork:

   ```bash
   git clone https://github.com/<your-username>/maestro.git
   cd maestro
   ```

3. **Add the upstream remote:**

   ```bash
   git remote add upstream https://github.com/<org>/maestro.git
   ```

4. **Verify the build runs:**

   ```bash
   ./gradlew build
   ```

   If the build succeeds, you are ready to contribute.

---

## Building

### Full build (all modules)

```bash
./gradlew build
```

### Build a specific module

```bash
./gradlew :maestro-core:build
./gradlew :maestro-store-postgres:build
./gradlew :maestro-spring-boot-starter:build
```

### Clean build

```bash
./gradlew clean build
```

### Module overview

| Module | Description |
|---|---|
| `maestro-core` | Pure Java engine. No Spring dependency. |
| `maestro-spring-boot-starter` | Spring Boot auto-configuration, annotations, bean proxying. |
| `maestro-store-jdbc` | Abstract JDBC `WorkflowStore` SPI implementation. |
| `maestro-store-postgres` | Postgres implementation with Flyway 11 migrations. |
| `maestro-messaging-kafka` | Spring Kafka 4.x `WorkflowMessaging` SPI implementation. |
| `maestro-lock-valkey` | Lettuce-based `DistributedLock` SPI implementation. |
| `maestro-admin` | Standalone dashboard (Thymeleaf + HTMX). |
| `maestro-admin-client` | Lightweight lifecycle event publisher. |
| `maestro-test` | In-memory SPIs, controllable clock, `TestWorkflowEnvironment`. |
| `maestro-samples/` | Example applications demonstrating Maestro usage. |

---

## Running Tests

### Unit tests

```bash
# All modules
./gradlew test

# Specific module
./gradlew :maestro-core:test
```

### Integration tests

Integration tests use [Testcontainers](https://testcontainers.com/) and **require Docker to be running**.

```bash
# All integration tests
./gradlew integrationTest

# Specific module
./gradlew :maestro-store-postgres:integrationTest
```

### Running a specific test class

```bash
./gradlew :maestro-core:test --tests "io.maestro.core.engine.WorkflowExecutorTest"
```

### Running a specific test method

```bash
./gradlew :maestro-core:test --tests "io.maestro.core.engine.WorkflowExecutorTest.shouldReplayCompletedActivities"
```

---

## Code Standards

Maestro follows strict coding standards. Please review these before submitting code.

### Java 25 Features

- **Records** for DTOs and value objects.
- **Sealed interfaces** where a closed set of implementations is appropriate.
- **Virtual threads** for workflow execution. Never block virtual threads with `synchronized` or legacy I/O.
- **Scoped values** (`ScopedValue`) for per-workflow context binding (replaces `ThreadLocal`).
- **`var`** for local variables where the type is obvious from context.
- **Pattern matching** for `instanceof` checks.

### No Lombok

Do not use Lombok. Use records for immutable data and IDE-generated code (constructors, getters, `equals`, `hashCode`, `toString`) for mutable classes.

### Null Safety with JSpecify

- Annotate all public API parameters and return types with `@Nullable` or `@NonNull` from `org.jspecify.annotations`.
- Prefer `Optional<T>` for return types that may be absent.
- Never pass `null` where `@NonNull` is declared.

### Jackson 3 Serialization

- Use `tools.jackson` packages exclusively.
- **Never** use `com.fasterxml.jackson` packages. Jackson 3 has a different package namespace.

### Jakarta EE 11

- Use `jakarta.*` packages exclusively.
- **Never** use `javax.*` packages. Spring Boot 4 targets Jakarta EE 11.

### Immutability

- **Records** for DTOs and event objects.
- **Final fields with builders** for mutable domain objects that need controlled construction.
- Favor immutable collections (`List.of()`, `Map.of()`, `Set.of()`).

### Exception Handling

- All exceptions must extend the `MaestroException` hierarchy.
- Use specific exception subtypes for distinct failure modes.
- Include meaningful context in exception messages.

### Logging

- Use **SLF4J** for all logging.
- Set MDC context with `workflowId`, `runId`, and `activityName` where applicable.
- Log at appropriate levels: `DEBUG` for internal flow, `INFO` for significant lifecycle events, `WARN` for recoverable issues, `ERROR` for failures.

### Javadoc

- All **public** classes, interfaces, and methods must have Javadoc.
- SPI interfaces (`WorkflowStore`, `WorkflowMessaging`, `DistributedLock`) require thorough documentation of contracts, thread-safety guarantees, and expected behavior.
- Include `@param`, `@return`, and `@throws` tags.

### Thread Safety

- Document thread-safety guarantees on all public classes (e.g., "This class is thread-safe" or "Instances of this class are not thread-safe").
- Use `java.util.concurrent` primitives where needed. Avoid `synchronized` blocks on virtual threads.

---

## Architecture Rules

These rules are non-negotiable. PRs that violate them will not be merged.

### `maestro-core` must never depend on Spring Framework

The core module is pure Java. It contains the engine, memoization logic, domain model, and SPI interfaces. This ensures Maestro's core can be embedded in non-Spring environments and keeps the dependency footprint minimal.

All Spring integration (auto-configuration, bean post-processors, annotation scanning) belongs in `maestro-spring-boot-starter`.

### SPIs live in core, implementations live in separate modules

The three SPI interfaces are defined in `maestro-core`:

- `WorkflowStore` -- persistence abstraction
- `WorkflowMessaging` -- messaging abstraction
- `DistributedLock` -- distributed locking abstraction

Implementations are in their respective modules (`maestro-store-postgres`, `maestro-messaging-kafka`, `maestro-lock-valkey`). This separation allows users to swap implementations without changing application code.

### Each module has a clear, single responsibility

Do not add cross-cutting concerns to the wrong module. If you are unsure where code belongs, open an issue to discuss before submitting a PR.

### Never auto-create Kafka topics

Kafka topics must be pre-created and declared in configuration. Auto-creation is unreliable in production environments and masks configuration errors.

### Postgres is truth, Valkey is optimization

All durable state is persisted to Postgres. Valkey/Redis is used only for distributed locks, deduplication caches, and pub/sub notifications. If Valkey is unavailable, the system must remain correct (though possibly slower).

### Workflow determinism

Code between activity calls in workflow methods must be deterministic. Do not use `Math.random()`, `LocalDateTime.now()`, `UUID.randomUUID()`, or direct I/O between activities. Use the utilities provided by `WorkflowContext` (`workflow.currentTime()`, `workflow.randomUUID()`).

---

## Making Changes

### Branch naming

Create a branch from `main` using one of these prefixes:

| Prefix | Use for |
|---|---|
| `feature/` | New functionality (e.g., `feature/add-cron-trigger`) |
| `fix/` | Bug fixes (e.g., `fix/signal-delivery-race-condition`) |
| `docs/` | Documentation changes (e.g., `docs/improve-spi-javadoc`) |
| `refactor/` | Code restructuring with no behavior change |
| `test/` | Adding or improving tests |

### Commit guidelines

- Keep commits focused. One logical change per commit.
- Write clear commit messages. Use the imperative mood in the subject line (e.g., "Add retry backoff to activity executor", not "Added retry backoff").
- Reference issue numbers where applicable (e.g., "Fix signal timeout handling (#42)").

### Before submitting

1. Run the full build: `./gradlew build`
2. Run unit tests: `./gradlew test`
3. Run integration tests if your change touches persistence, messaging, or locking: `./gradlew integrationTest`
4. Verify your code follows the [code standards](#code-standards) above.
5. Add or update tests for any new or changed functionality.

---

## Pull Request Process

1. **Fork the repository** and create a feature branch from `main`.

2. **Write tests** for new functionality. Bug fixes should include a regression test that fails without the fix.

3. **Ensure all checks pass:**

   ```bash
   ./gradlew build
   ```

4. **Submit your PR** with a clear description that includes:
   - **What** the change does.
   - **Why** it is needed (link to the issue if one exists).
   - **How** it was tested.
   - Any **breaking changes** or migration steps.

5. **Address review feedback.** Maintainers may request changes. Push additional commits to your branch rather than force-pushing, so the review history is preserved.

6. **Merge.** Once approved, a maintainer will merge your PR. Do not merge your own PRs.

### What makes a good PR

- Focused scope. One concern per PR.
- Tests included for new behavior.
- No unrelated formatting or refactoring changes mixed in.
- Javadoc updated for any new or changed public API.
- Build passes cleanly.

---

## Reporting Issues

### Bug reports

When filing a bug report, please include:

- **Summary:** A clear, concise description of the bug.
- **Steps to reproduce:** Numbered steps to reliably reproduce the issue.
- **Expected behavior:** What you expected to happen.
- **Actual behavior:** What actually happened, including error messages and stack traces.
- **Environment:**
  - Java version (`java --version`)
  - Spring Boot version
  - Maestro version
  - Database (Postgres version)
  - Message broker (Kafka version)
  - Operating system
- **Relevant configuration:** Any `maestro.*` properties, workflow definitions, or SPI configurations.

### Feature requests

Feature requests are welcome. Please include:

- **Problem statement:** What problem does this feature solve?
- **Proposed solution:** How do you envision this working?
- **Alternatives considered:** What other approaches did you consider?
- **Use case:** A concrete example of how you would use this feature.

### Questions and discussions

For questions about using Maestro, architecture decisions, or design discussions, please open a GitHub Discussion rather than an issue.

---

## License

Maestro is licensed under the [Apache License 2.0](LICENSE).

By submitting a contribution (code, documentation, or other material), you agree that your contribution will be licensed under the Apache License 2.0. You represent that you have the right to license your contribution under this license.

If you are contributing on behalf of your employer, please ensure you have the necessary permissions to do so.
