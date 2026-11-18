package io.b2mash.maestro.spring.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared-table startup check as the starter runs it (pilot readiness P3;
 * Issues 39, 78, 79): at context refresh, active instances under any other
 * {@code maestro.service-name} — whatever their workflow type — fail startup
 * by default; {@code maestro.store.foreign-service-check=warn} reports them
 * and starts; {@code off} reads nothing. A failed read is inconclusive and
 * never fails startup. The assertions use messages and properties only.
 */
class StartupForeignServiceCheckTest {

    private static final String SERVICE = "orders";
    private static final String CHECK_LOGGER = "io.b2mash.maestro.core.engine.ForeignServiceCheck";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger checkLog;
    private Level previousLevel;

    @BeforeEach
    void captureTheChecksLog() {
        checkLog = (Logger) LoggerFactory.getLogger(CHECK_LOGGER);
        previousLevel = checkLog.getLevel();
        checkLog.setLevel(Level.DEBUG);
        appender.start();
        checkLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        checkLog.detachAppender(appender);
        checkLog.setLevel(previousLevel);
    }

    @Test
    void activeRowsOfAnotherServiceOfAnyWorkflowTypeFailStartupByDefault() {
        var store = new InMemoryWorkflowStore();
        seed(store, "billing-1", "billing", "InvoiceWorkflow", WorkflowStatus.RUNNING);
        seed(store, "billing-2", "billing", "InvoiceWorkflow", WorkflowStatus.WAITING_TIMER);
        seed(store, "own-1", SERVICE, "OrderWorkflow", WorkflowStatus.RUNNING);

        runner(store).run(ctx -> {
            assertThat(ctx).hasFailed();
            // Thrown from a SmartInitializingSingleton: it is the startup failure itself.
            assertThat(ctx.getStartupFailure())
                    .hasMessageContaining("'billing' (2)")
                    .hasMessageContaining("Services never share Maestro tables")
                    .hasMessageContaining("maestro.store.foreign-service-check=warn");
        });
    }

    @Test
    void warnReportsTheRowsAndStartsForARenameInProgress() {
        var store = new InMemoryWorkflowStore();
        seed(store, "old-1", "orders-old", "OrderWorkflow", WorkflowStatus.RUNNING);
        seed(store, "old-2", "orders-old", "OrderWorkflow", WorkflowStatus.WAITING_SIGNAL);

        runner(store).withPropertyValues("maestro.store.foreign-service-check=warn").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().getFirst()).startsWith("2 active workflow instance(s) are recorded under "
                    + "maestro.service-name 'orders-old', not 'orders'");
        });
    }

    @Test
    void offReadsNothingAndStarts() {
        var store = new InMemoryWorkflowStore();
        seed(store, "old-1", "orders-old", "OrderWorkflow", WorkflowStatus.RUNNING);

        runner(store).withPropertyValues("maestro.store.foreign-service-check=off").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(messagesAboveDebug()).isEmpty();
        });
    }

    @Test
    void terminalRowsOfAnotherServiceDoNotFailStartup() {
        var store = new InMemoryWorkflowStore();
        seed(store, "old-1", "orders-old", "OrderWorkflow", WorkflowStatus.COMPLETED);

        runner(store).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aCleanStoreStartsAndLogsNothingAboveDebug() {
        var store = new InMemoryWorkflowStore();
        seed(store, "own-1", SERVICE, "OrderWorkflow", WorkflowStatus.RUNNING);

        runner(store).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(messagesAboveDebug()).isEmpty();
        });
    }

    @Test
    void aFailedReadIsInconclusiveAndNeverFailsStartup() {
        var store = failingCountsRead(new InMemoryWorkflowStore());

        runner(store).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(warnings()).anySatisfy(w -> assertThat(w)
                    .startsWith("Maestro shared-table check inconclusive")
                    .contains("injected: the count query failed"));
        });
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static ApplicationContextRunner runner(WorkflowStore store) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WorkflowStore.class, () -> store)
                .withBean(DistributedLock.class, InMemoryDistributedLock::new)
                .withPropertyValues("maestro.service-name=" + SERVICE);
    }

    /** A store whose service-name read throws; every other call reaches {@code delegate}. */
    private static WorkflowStore failingCountsRead(InMemoryWorkflowStore delegate) {
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getActiveInstanceCountsUnderOtherServices")) {
                        throw new IllegalStateException("injected: the count query failed");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void seed(InMemoryWorkflowStore store, String workflowId, String serviceName, String type,
                             WorkflowStatus status) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType(type)
                .taskQueue("default")
                .status(status)
                .serviceName(serviceName)
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .completedAt(status.isActive() ? null : now)
                .version(0)
                .build());
    }

    private List<String> messagesAboveDebug() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private List<String> warnings() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }
}
