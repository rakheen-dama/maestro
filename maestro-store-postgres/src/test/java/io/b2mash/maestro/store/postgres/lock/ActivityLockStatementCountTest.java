package io.b2mash.maestro.store.postgres.lock;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The lock-table statements one workflow run costs on Postgres: a live
 * activity issues none (3.10). Before the per-activity lock was removed, each
 * live activity cost an {@code INSERT … ON CONFLICT … RETURNING} and a
 * {@code DELETE} against {@code maestro_distributed_lock}.
 *
 * <p>Only the lock's {@link javax.sql.DataSource} is counted; the store runs on
 * an uncounted one. Runs against a fresh database migrated by
 * {@link MaestroSchemaInitializer}, on the lock suites' shared container.
 */
@DisplayName("A five-activity workflow costs only its instance lock's two lock-table statements (3.10)")
class ActivityLockStatementCountTest {

    private static final int ACTIVITIES = 5;

    private @Nullable WorkflowExecutor executor;

    @AfterEach
    void stop() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a live activity issues no lock statement: the run's only lock statements are the instance "
            + "lock's acquire and release")
    void liveActivitiesIssueNoLockStatement() throws Exception {
        var dataSource = freshMigratedDatabase();
        var counting = new StatementCountingDataSource(dataSource);
        var lock = new PostgresDistributedLock(counting);
        var mapper = JsonMapper.builder().build();
        var store = new PostgresWorkflowStore(dataSource, mapper);
        var serializer = new PayloadSerializer(mapper);
        executor = WorkflowExecutor.builder(store, serializer, "lock-count-svc")
                .distributedLock(lock)
                .lockKeyPrefix("maestro:lock:")
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .build();
        var steps = new ActivityProxyFactory().createProxy(Steps.class, input -> input + "+", store, lock, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(), "maestro:lock:");
        var method = FiveSteps.class.getMethod("run", String.class);

        counting.reset();
        executor.startWorkflow("lock-count-1", "FiveSteps", "default", "x", () -> new FiveSteps(steps), method);
        await().atMost(Duration.ofSeconds(15)).until(() -> store.getInstance("lock-count-1")
                .map(i -> i.status() == WorkflowStatus.COMPLETED).orElse(false));
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("lock-count-1"));

        assertEquals("x" + "+".repeat(ACTIVITIES),
                store.getInstance("lock-count-1").orElseThrow().output().stringValue());
        assertEquals(1, counting.executedStartingWith("INSERT"), "one acquire: the instance lock's");
        assertEquals(1, counting.executedStartingWith("DELETE"), "one release: the instance lock's");
        assertEquals(2, counting.executedStartingWith(""),
                "lock-table statements for a " + ACTIVITIES + "-activity run");
    }

    private static PGSimpleDataSource freshMigratedDatabase() throws Exception {
        var container = PostgresLockTestSupport.postgres;
        var name = "lock_count_" + UUID.randomUUID().toString().replace("-", "");
        try (var conn = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + name);
        }
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {container.getHost()});
        ds.setPortNumbers(new int[] {container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        ds.setDatabaseName(name);
        ds.setUser(container.getUsername());
        ds.setPassword(container.getPassword());
        new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, ActivityLockStatementCountTest.class.getClassLoader())
                .initialize();
        return ds;
    }

    /** One activity, called five times. */
    public interface Steps {
        /** @param input the value so far @return it with one more {@code +} */
        String step(String input);
    }

    /** Calls {@link Steps#step} five times. */
    @DurableWorkflow(name = "FiveSteps")
    public static class FiveSteps {
        private final Steps steps;

        FiveSteps(Steps steps) {
            this.steps = steps;
        }

        /** @param input the input @return the input after five steps */
        @WorkflowMethod
        public String run(String input) {
            var value = input;
            for (var i = 0; i < ACTIVITIES; i++) {
                value = steps.step(value);
            }
            return value;
        }
    }
}
