package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopSnapshot;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery poller after a failed pass (P2b review m1, m2) and its failing
 * rule (m3).
 *
 * <ul>
 *   <li>m1: a first pass that fails does not count as the startup pass — the
 *       first <em>successful</em> pass notes {@code firstPassComplete}, so
 *       health's {@code startupRecovery} reads {@code complete}.</li>
 *   <li>m2: a prompt (a start deferred for its lock) ends the back-off after a
 *       failed pass, no sooner than {@code capacitySpacing} after the request,
 *       and causes one pass, not two; a storm of prompts during an outage runs
 *       at most one pass per spacing.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> per-test fixtures; the counters are atomic.
 */
class RecoveryPollerFailedPassTest {

    private final AtomicInteger passes = new AtomicInteger();
    private final List<WorkflowExecutor> executors = new ArrayList<>();
    private final List<RunningLoop> loops = new ArrayList<>();

    @AfterEach
    void tearDown() {
        loops.forEach(RunningLoop::stop);
        executors.forEach(WorkflowExecutor::shutdown);
    }

    /** A store whose paged recovery read throws when {@code fails} holds for the call's number (from 1). */
    private WorkflowStore failing(IntPredicate fails) {
        var delegate = new InMemoryWorkflowStore();
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getRecoverableInstances") && args != null && args.length == 3
                            && fails.test(passes.incrementAndGet())) {
                        throw new IllegalStateException("store down (scripted)");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private WorkflowExecutor executor(WorkflowStore store) {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .build();
        executors.add(executor);
        return executor;
    }

    private RunningLoop run(RecoveryPoller body) {
        var loop = RunningLoop.start(body.spec(), body);
        loops.add(loop);
        return loop;
    }

    @Test
    void aFailedFirstPassIsNotTheStartupPassTheFirstSuccessfulOneIs() {
        var executor = executor(failing(call -> call == 1));
        var loop = run(new RecoveryPoller(executor, Map.of(), "svc", Duration.ofMillis(50)));
        await().atMost(Duration.ofSeconds(5)).until(() -> passes.get() >= 2);
        await("the first successful pass notes it").atMost(Duration.ofSeconds(5))
                .until(() -> Boolean.TRUE.equals(snapshot(loop).notes().get(RecoveryPoller.FIRST_PASS_COMPLETE)));
    }

    @Test
    void aPromptEndsTheBackOffOfAFailedPassAndCausesOnePass() {
        var executor = executor(failing(call -> call == 1));
        var body = new RecoveryPoller(executor, Map.of(), "svc", Duration.ofHours(1),
                executor.taskQueueGovernor().capacitySignal(), Duration.ofMillis(200));
        run(body);
        await("the failed first pass").atMost(Duration.ofSeconds(5)).until(() -> passes.get() >= 1);

        body.requestPromptPass();

        await("the prompt pass, long before the 1 h back-off ends").atMost(Duration.ofSeconds(5))
                .until(() -> passes.get() >= 2);
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2)).until(() -> passes.get() == 2);
    }

    @Test
    void promptsDuringAnOutageRunAtMostOnePassPerSpacing() throws Exception {
        var executor = executor(call -> true);
        var body = new RecoveryPoller(executor, Map.of(), "svc", Duration.ofHours(1),
                executor.taskQueueGovernor().capacitySignal(), Duration.ofMillis(200));
        run(body);
        await("the failed first pass").atMost(Duration.ofSeconds(5)).until(() -> passes.get() >= 1);

        var end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() - end < 0) {
            body.requestPromptPass();
            Thread.sleep(5);
        }
        var during = passes.get() - 1;
        assertTrue(during >= 2, "prompts end the back-off: " + during + " passes in 1 s");
        assertTrue(during <= 6, "at most one prompt pass per 200 ms spacing: " + during + " passes in 1 s");
    }

    @Test
    void theFailingRuleIsTwoFailedPassesOverMax120sAndTwoIntervals() {
        var executor = executor(new InMemoryWorkflowStore());
        assertEquals(FailingRule.streakFor(2, Duration.ofSeconds(120)),
                new RecoveryPoller(executor, Map.of(), "svc", Duration.ofSeconds(60)).spec().failing());
        assertEquals(FailingRule.streakFor(2, Duration.ofSeconds(180)),
                new RecoveryPoller(executor, Map.of(), "svc", Duration.ofSeconds(90)).spec().failing());
    }

    private WorkflowExecutor executor(IntPredicate fails) {
        return executor(failing(fails));
    }

    private static LoopSnapshot snapshot(RunningLoop loop) {
        return loop.supervisor().snapshot();
    }
}
