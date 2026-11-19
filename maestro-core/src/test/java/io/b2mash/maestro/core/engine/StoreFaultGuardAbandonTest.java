package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pilot readiness P1, task 2 (Issue 70): once shutdown has given up on a run
 * ({@code RunCancellation.abandon()}), {@link StoreFaultGuard} refuses every
 * {@link WorkflowStore} call made under that run's bound context with
 * {@link ExecutorShutdownException}, without reaching the store — every method
 * of the SPI, enumerated by reflection so a method added later is covered —
 * and refuses none made inside {@code advisory(...)} or
 * {@code passThrough(...)}, nor any made with no run bound.
 */
class StoreFaultGuardAbandonTest {

    private static final List<Duration> NO_WAIT = List.of(Duration.ZERO, Duration.ZERO);

    @TestFactory
    Stream<DynamicTest> everyStoreMethodIsRefusedForAnAbandonedRunAndNoneUnderAdvisoryOrPassThrough() {
        return storeMethods().flatMap(method -> Stream.of(
                DynamicTest.dynamicTest(method.getName() + signature(method) + " refused when abandoned", () -> {
                    var store = new Recording();
                    var ctx = abandonedContext();
                    var thrown = inRun(ctx, () -> call(StoreFaultGuard.wrap(store.proxy(), NO_WAIT), method));
                    assertInstanceOf(ExecutorShutdownException.class, thrown, method.toString());
                    assertTrue(store.calls.isEmpty(), "the store was reached: " + store.calls);
                }),
                DynamicTest.dynamicTest(method.getName() + signature(method) + " passes under advisory", () -> {
                    var store = new Recording();
                    var thrown = inRun(abandonedContext(), () -> StoreFaultGuard.advisory(
                            () -> call(StoreFaultGuard.wrap(store.proxy(), NO_WAIT), method)));
                    assertEquals(null, thrown, method.toString());
                    assertEquals(List.of(method.getName()), store.calls);
                }),
                DynamicTest.dynamicTest(method.getName() + signature(method) + " passes under passThrough", () -> {
                    var store = new Recording();
                    var thrown = inRun(abandonedContext(), () -> {
                        try {
                            return StoreFaultGuard.passThrough(
                                    () -> call(StoreFaultGuard.wrap(store.proxy(), NO_WAIT), method));
                        } catch (Exception e) {
                            return e;
                        }
                    });
                    assertEquals(null, thrown, method.toString());
                    assertEquals(List.of(method.getName()), store.calls);
                }),
                DynamicTest.dynamicTest(method.getName() + signature(method) + " passes with no run bound", () -> {
                    var store = new Recording();
                    assertEquals(null, call(StoreFaultGuard.wrap(store.proxy(), NO_WAIT), method));
                    assertEquals(List.of(method.getName()), store.calls);
                }),
                DynamicTest.dynamicTest(method.getName() + signature(method) + " passes for a run not given up", () -> {
                    var store = new Recording();
                    var ctx = new WorkflowContext(UUID.randomUUID(), "wf-live", UUID.randomUUID(), "T", "default",
                            "svc", 0, false);
                    ctx.cancellation().requestShutdown(); // a drain in progress is not a give-up
                    assertEquals(null, inRun(ctx, () -> call(StoreFaultGuard.wrap(store.proxy(), NO_WAIT), method)));
                    assertEquals(List.of(method.getName()), store.calls);
                })));
    }

    /**
     * A retried call whose run is given up while it waits out its backoff
     * sends nothing more: the second attempt is refused before the store.
     *
     * <p>Merge guard (P1 review I3): this fails if the give-up check after
     * the backoff wait in {@code StoreFaultGuard.Handler.invoke} is dropped —
     * the entry check alone does not cover it. Keep both when the loop
     * supervisor's retry-episode rework of that loop is merged.
     */
    @org.junit.jupiter.api.Test
    void aRunGivenUpDuringARetryBackoffSendsNoFurtherAttempt() {
        var ctx = new WorkflowContext(UUID.randomUUID(), "wf-retrying", UUID.randomUUID(), "T", "default",
                "svc", 0, false);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getEventBySequence")) {
                        attempts.incrementAndGet();
                        ctx.cancellation().abandon(); // the deadline passes during this attempt
                        throw new io.b2mash.maestro.core.exception.StoreUnavailableException(
                                "store down (test)", new IllegalStateException());
                    }
                    return placeholder(method.getReturnType());
                });
        var guarded = StoreFaultGuard.wrap(delegate, NO_WAIT);

        var thrown = inRun(ctx, () -> {
            try {
                guarded.getEventBySequence(UUID.randomUUID(), 1);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        assertInstanceOf(ExecutorShutdownException.class, thrown);
        assertEquals(1, attempts.get(), "the retry after the give-up reached the store");
    }

    /** The SPI's instance methods, abstract and default, in a stable order. */
    private static Stream<Method> storeMethods() {
        var methods = Arrays.stream(WorkflowStore.class.getMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .sorted(Comparator.comparing(Method::getName).thenComparing(StoreFaultGuardAbandonTest::signature))
                .toList();
        assertFalse(methods.isEmpty());
        return methods.stream();
    }

    private static String signature(Method method) {
        return Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList().toString();
    }

    private static WorkflowContext abandonedContext() {
        var ctx = new WorkflowContext(UUID.randomUUID(), "wf-abandoned", UUID.randomUUID(), "T", "default",
                "svc", 0, false);
        ctx.cancellation().abandon();
        return ctx;
    }

    /** Runs {@code body} with {@code ctx} bound, as a run's thread would; returns what it threw or returned. */
    private static @Nullable Object inRun(WorkflowContext ctx, java.util.function.Supplier<@Nullable Object> body) {
        return ScopedValue.where(WorkflowContext.scopedValue(), ctx).call(body::get);
    }

    /** Calls {@code method} on {@code store} with placeholder arguments; returns its throwable, else {@code null}. */
    private static @Nullable Throwable call(WorkflowStore store, Method method) {
        var args = Arrays.stream(method.getParameterTypes()).map(StoreFaultGuardAbandonTest::placeholder).toArray();
        try {
            method.invoke(store, args);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (IllegalAccessException e) {
            return fail(e);
        }
    }

    private static @Nullable Object placeholder(Class<?> type) {
        if (type == int.class) {
            return 1;
        }
        if (type == long.class) {
            return 1L;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }

    /** A delegate store that records the name of each call and answers with a placeholder. */
    private static final class Recording {
        final List<String> calls = new CopyOnWriteArrayList<>();

        WorkflowStore proxy() {
            return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                    new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                default -> "Recording";
                            };
                        }
                        calls.add(method.getName());
                        return placeholder(method.getReturnType());
                    });
        }
    }
}
