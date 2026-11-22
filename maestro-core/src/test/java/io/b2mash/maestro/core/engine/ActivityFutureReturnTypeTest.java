package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.MaestroException;
import io.b2mash.maestro.core.exception.UnsupportedActivityReturnTypeException;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.core.observe.EngineObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 49: an activity method declared to return a {@link Future} or a
 * {@link CompletionStage} (or any subtype) cannot work across a restart — its
 * recorded JSON is the future's bean properties, never its value — so
 * {@link ActivityProxyFactory#createProxy} refuses the interface, naming it
 * and every such method. The Spring starter builds its proxies at context
 * start ({@code WorkflowRegistrar} → {@code ActivityStubBeanPostProcessor.prepare})
 * and the test kit checks at {@code startWorkflow}, so the refusal is a
 * startup failure there, before anything is persisted.
 */
@DisplayName("Future-typed activity results are refused at proxy creation (Issue 49)")
class ActivityFutureReturnTypeTest {

    private final ActivityProxyFactory factory = new ActivityProxyFactory();
    private final WorkflowStore store = (WorkflowStore) Proxy.newProxyInstance(
            WorkflowStore.class.getClassLoader(), new Class<?>[]{WorkflowStore.class},
            (p, m, a) -> { throw new UnsupportedOperationException("not called at proxy creation"); });

    interface CompletableFutureActivities {
        CompletableFuture<String> fetch(String id);

        String plain(String id);
    }

    interface FutureActivities {
        Future<String> fetch();
    }

    interface StageActivities {
        CompletionStage<String> fetch();
    }

    /** A Future subtype that is not a CompletableFuture: assignable, so refused too. */
    interface FutureTaskActivities {
        FutureTask<String> fetch();
    }

    /** Inherited from a super-interface: still an activity method of the proxy. */
    interface InheritingActivities extends StageActivities {
        String plain();
    }

    /** A generic parent whose result type is a type variable (erases to Object). */
    interface GenericParent<T> {
        T result();
    }

    /** The parent specialised to a future: {@code result()} returns a CompletableFuture here. */
    interface FutureChild extends GenericParent<CompletableFuture<String>> {
        String plain();
    }

    /** Two levels down, through a type variable passed along. */
    interface Middle<U> extends GenericParent<U> { }

    interface StageGrandchild extends Middle<CompletionStage<Integer>> { }

    /** The same parent specialised to a plain value: accepted. */
    interface StringChild extends GenericParent<String> { }

    /** Two offending methods: both are named. */
    interface TwoFutures {
        CompletableFuture<String> first();

        CompletionStage<Integer> second();

        String fine();
    }

    /** No future anywhere; a future as a parameter is not a result. */
    interface Fine {
        String run(CompletableFuture<String> notAResult);

        List<String> list();

        Object anything();

        void nothing();

        /** Not an activity call: a proxy never dispatches a static method. */
        static CompletableFuture<String> helper() {
            return CompletableFuture.completedFuture("x");
        }
    }

    @Test
    @DisplayName("a CompletableFuture return type is refused, naming the interface and the method")
    void aCompletableFutureIsRefused() {
        var refused = assertThrows(UnsupportedActivityReturnTypeException.class,
                () -> proxy(CompletableFutureActivities.class));
        assertInstanceOf(MaestroException.class, refused);
        assertEquals(CompletableFutureActivities.class.getName(), refused.activityInterface());
        assertEquals(List.of("fetch"), refused.methods());
        var message = refused.getMessage();
        assertTrue(message.contains(CompletableFutureActivities.class.getName()), message);
        assertTrue(message.contains(
                "fetch(String) returns java.util.concurrent.CompletableFuture<java.lang.String>"), message);
        assertFalse(message.contains("plain"), message);
    }

    @Test
    @DisplayName("a Future, a CompletionStage and a FutureTask return type are refused")
    void everyFutureOrStageIsRefused() {
        for (var iface : List.of(FutureActivities.class, StageActivities.class, FutureTaskActivities.class)) {
            var refused = assertThrows(UnsupportedActivityReturnTypeException.class, () -> proxy(iface),
                    iface.getSimpleName());
            assertTrue(refused.getMessage().contains(iface.getName()), refused.getMessage());
            assertTrue(refused.getMessage().contains("fetch() returns"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("an inherited future method is refused under the proxied interface's name")
    void anInheritedFutureIsRefused() {
        var refused = assertThrows(UnsupportedActivityReturnTypeException.class,
                () -> proxy(InheritingActivities.class));
        assertEquals(InheritingActivities.class.getName(), refused.activityInterface());
        assertEquals(List.of("fetch"), refused.methods());
    }

    @Test
    @DisplayName("a generic parent's T result() specialised to a CompletableFuture is refused, naming the resolved type")
    void aGenericParentSpecialisedToAFutureIsRefused() {
        var refused = assertThrows(UnsupportedActivityReturnTypeException.class, () -> proxy(FutureChild.class));
        assertEquals(FutureChild.class.getName(), refused.activityInterface());
        assertEquals(List.of("result"), refused.methods());
        assertTrue(refused.getMessage().contains(
                "result() returns java.util.concurrent.CompletableFuture<java.lang.String>"), refused.getMessage());
    }

    @Test
    @DisplayName("a type variable passed through an intermediate interface is resolved too")
    void aTypeVariablePassedThroughIsResolved() {
        var refused = assertThrows(UnsupportedActivityReturnTypeException.class,
                () -> proxy(StageGrandchild.class));
        assertEquals(List.of("result"), refused.methods());
        assertTrue(refused.getMessage().contains(
                "result() returns java.util.concurrent.CompletionStage<java.lang.Integer>"), refused.getMessage());
    }

    @Test
    @DisplayName("a generic parent specialised to a plain value, or left unbound, is accepted")
    void aGenericParentSpecialisedToAValueIsAccepted() {
        assertInstanceOf(StringChild.class, proxy(StringChild.class));
        assertDoesNotThrow(() -> ActivityProxyFactory.requireSupportedReturnTypes(GenericParent.class));
    }

    @Test
    @DisplayName("every offending method is named, in name order")
    void everyOffendingMethodIsNamed() {
        var refused = assertThrows(UnsupportedActivityReturnTypeException.class, () -> proxy(TwoFutures.class));
        assertEquals(List.of("first", "second"), refused.methods());
    }

    @Test
    @DisplayName("an interface with no future result, a future parameter or a static helper, is accepted")
    void noFutureResultIsAccepted() {
        assertInstanceOf(Fine.class, proxy(Fine.class));
    }

    @Test
    @DisplayName("the check is callable on its own (the test kit's start-time preflight)")
    void theCheckIsCallableOnItsOwn() {
        assertDoesNotThrow(() -> ActivityProxyFactory.requireSupportedReturnTypes(Fine.class));
        assertThrows(UnsupportedActivityReturnTypeException.class,
                () -> ActivityProxyFactory.requireSupportedReturnTypes(StageActivities.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> iface) {
        // The implementation is never called: proxy creation only.
        var impl = (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (p, m, a) -> { throw new UnsupportedOperationException(); });
        return factory.createProxy(iface, impl, store, RetryPolicy.defaultPolicy(),
                Duration.ofSeconds(1), new PayloadSerializer(new ObjectMapper()), new RetryExecutor(),
                EngineObserver.NOOP, TaskQueueGovernor.UNBOUNDED,
                LifecycleEventSink.NOOP, true);
    }
}
