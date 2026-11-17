package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5 D-9: each background loop stops in two halves. {@link StoppableLoop#requestStop()}
 * returns at once even while the loop is stuck in a store call that swallows
 * the interrupt, and {@link StoppableLoop#awaitStopped(Duration)} waits no
 * longer than its bound — so a shutdown can ask all four loops first and join
 * them against one deadline.
 */
@DisplayName("Background loops stop in two halves")
class BackgroundLoopStopTest {

    private static final Duration INTERVAL = Duration.ofMillis(20);

    /** The four loops, each built over the stuck store with its package-private constructor. */
    enum Loop {
        TIMER_POLLER {
            @Override
            StoppableLoop create(StuckStore store, WorkflowExecutor executor) {
                var loop = new TimerPoller(store, executor, null, "svc", INTERVAL, 10);
                loop.start();
                return loop;
            }
        },
        RECOVERY_POLLER {
            @Override
            StoppableLoop create(StuckStore store, WorkflowExecutor executor) {
                var loop = new RecoveryPoller(executor, Map.of(), "svc", INTERVAL);
                loop.start();
                return loop;
            }
        },
        SIGNAL_JANITOR {
            @Override
            StoppableLoop create(StuckStore store, WorkflowExecutor executor) {
                var loop = new SignalJanitor(store, "svc", INTERVAL, Duration.ofDays(1));
                loop.start();
                return loop;
            }
        },
        RETENTION_SWEEPER {
            @Override
            StoppableLoop create(StuckStore store, WorkflowExecutor executor) {
                var loop = new RetentionSweeper(store, null, "svc", new WorkflowExecutor.RetentionSettings(
                        INTERVAL, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 10, 1),
                        EngineObserver.NOOP);
                loop.start();
                return loop;
            }
        };

        abstract StoppableLoop create(StuckStore store, WorkflowExecutor executor);

        StoppableLoop start(StuckStore store, WorkflowExecutor executor) {
            return create(store, executor);
        }
    }

    private StuckStore store;
    private WorkflowExecutor executor;
    private @Nullable StoppableLoop loop;

    @BeforeEach
    void setUp() {
        store = new StuckStore();
        executor = WorkflowExecutor.builder(store, new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .lifecycleEventsEnabled(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        store.release.countDown();
        if (loop != null) {
            loop.requestStop();
            loop.awaitStopped(Duration.ofSeconds(5));
        }
        executor.shutdown();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Loop.class)
    void requestStopReturnsAtOnceAndAwaitStoppedHonoursItsBound(Loop kind) throws Exception {
        var loop = kind.start(store, executor);
        this.loop = loop;
        assertTrue(store.entered.await(5, SECONDS), "the loop is inside its store call");
        var t0 = System.nanoTime();
        loop.requestStop();
        var requestMs = (System.nanoTime() - t0) / 1_000_000;
        var stoppedWhileStuck = loop.awaitStopped(Duration.ofMillis(100));
        store.release.countDown();
        var stoppedOnceReturned = loop.awaitStopped(Duration.ofSeconds(5));
        assertAll(
                () -> assertTrue(requestMs < 200, "requestStop() took " + requestMs + " ms"),
                () -> assertFalse(stoppedWhileStuck, "the loop is still inside its store call"),
                () -> assertTrue(stoppedOnceReturned, "the loop ended once its store call returned"));
    }

    /**
     * Blocks each loop's store call on {@link #release}, swallowing interrupts
     * the way a driver that ignores them does, and counts down
     * {@link #entered} on the way in.
     */
    static final class StuckStore extends VersionedInMemoryStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public List<WorkflowTimer> getDueTimers(int batchSize) {
            block();
            return List.of();
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances(
                String serviceName, @Nullable RecoveryCursor after, int limit) {
            block();
            return List.of();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            block();
            return 0;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            block();
            return PurgeResult.EMPTY;
        }

        private void block() {
            entered.countDown();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException swallowed) {
                    // a driver that swallows the interrupt (flag cleared) and keeps waiting
                }
            }
        }
    }
}
