package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.messaging.kafka.MaestroSignalHeaders;
import io.b2mash.maestro.messaging.kafka.MockEngines;
import io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.MessageListener;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A {@code @MaestroSignalListener} method receives its payload read with the
 * method's <em>generic</em> parameter type (Issue 92, "Kafka listener payload
 * type"; the listener-side twin of Issue 44): a {@code List<Order>} parameter
 * receives {@code Order}s, not {@code LinkedHashMap}s.
 *
 * <p>Drives the real container's listener lambda with a hand-built record, as
 * {@code MaestroSignalListenerContainerConfigTest} does; no broker.
 */
@DisplayName("@MaestroSignalListener payload type")
class MaestroSignalListenerPayloadTypeTest {

    public record Order(String id, BigDecimal amount) {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class, ListenerConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowExecutor.class, MockEngines::executor)
            .withPropertyValues(
                    "maestro.service-name=listener-payload-type-test",
                    "spring.kafka.bootstrap-servers=localhost:19092",
                    "maestro.messaging.topics.verify-on-startup=off");

    @Test
    @DisplayName("a List<Order> parameter receives Orders")
    void aListOfRecordsReceivesRecords() {
        runner.run(ctx -> {
            deliver(ctx, "orders.list", "[{\"id\":\"o-1\",\"amount\":10.50},{\"id\":\"o-2\",\"amount\":3}]");

            var received = ctx.getBean(TypedListeners.class).list.get();
            assertThat(received).isNotNull();
            assertThat(received).containsExactly(
                    new Order("o-1", new BigDecimal("10.50")), new Order("o-2", new BigDecimal("3")));
            assertThat(received.getFirst()).isInstanceOf(Order.class);
            verify(ctx.getBean(WorkflowExecutor.class))
                    .deliverSignal(eq("wf-list"), eq("list.signal"), any(), anyString());
        });
    }

    @Test
    @DisplayName("a Map<String, List<Order>> parameter receives Orders at the nested level")
    void aNestedGenericReceivesRecords() {
        runner.run(ctx -> {
            deliver(ctx, "orders.nested", "{\"eu\":[{\"id\":\"o-3\",\"amount\":1.0}],\"us\":[]}");

            var received = ctx.getBean(TypedListeners.class).nested.get();
            assertThat(received).isNotNull();
            assertThat(received).containsOnlyKeys("eu", "us");
            assertThat(received.get("eu")).containsExactly(new Order("o-3", new BigDecimal("1.0")));
            assertThat(received.get("eu").getFirst()).isInstanceOf(Order.class);
            assertThat(received.get("us")).isEmpty();
        });
    }

    @Test
    @DisplayName("a plain class parameter reads as before")
    void aPlainClassReadsAsBefore() {
        runner.run(ctx -> {
            deliver(ctx, "orders.plain", "{\"id\":\"o-4\",\"amount\":2}");

            assertThat(ctx.getBean(TypedListeners.class).plain.get())
                    .isEqualTo(new Order("o-4", new BigDecimal("2")));
        });
    }

    @Test
    @DisplayName("a JSON null payload reaches the method as null, under a generic parameter too")
    void aJsonNullPayloadIsNull() {
        runner.run(ctx -> {
            var listeners = ctx.getBean(TypedListeners.class);
            listeners.list.set(List.of(new Order("sentinel", BigDecimal.ONE)));

            deliver(ctx, "orders.list", "null");

            assertThat(listeners.listCalls).hasValue(1);
            assertThat(listeners.list.get()).isNull();
            verify(ctx.getBean(WorkflowExecutor.class))
                    .deliverSignal(eq("wf-list"), eq("list.signal"), any(), anyString());
        });
    }

    @Test
    @DisplayName("a record with no value (a tombstone) is refused before the method runs, as before")
    void aTombstoneIsRefusedAsBefore() {
        runner.run(ctx -> {
            var listener = listenerFor(ctx, "orders.list");
            var record = new ConsumerRecord<String, byte[]>("orders.list", 0, 7L, "key", null);
            MaestroSignalHeaders.setMessageId(record.headers(), "evt-tombstone");

            assertThatThrownBy(() -> listener.onMessage(record))
                    .hasMessageContaining("signals must not be discarded");
            assertThat(ctx.getBean(TypedListeners.class).listCalls).hasValue(0);
            verify(ctx.getBean(WorkflowExecutor.class), never())
                    .deliverSignal(anyString(), anyString(), any(), any());
        });
    }

    @Test
    @DisplayName("a listener method inherited from a generic superclass reads the type the bean's class binds (Issue 94)")
    void anInheritedGenericParameterReadsTheBoundType() {
        runner.run(ctx -> {
            deliver(ctx, "orders.inherited", "{\"id\":\"o-5\",\"amount\":4}");

            var listener = ctx.getBean(InheritedOrderListener.class);
            assertThat(listener.received.get()).isEqualTo(new Order("o-5", new BigDecimal("4")));
            verify(ctx.getBean(WorkflowExecutor.class))
                    .deliverSignal(eq("wf-o-5"), eq("inherited.signal"), any(), anyString());
        });
    }

    private static void deliver(AssertableApplicationContext ctx, String topic, String json) {
        var record = new ConsumerRecord<String, byte[]>(topic, 0, 0L, "key",
                json.getBytes(StandardCharsets.UTF_8));
        // A header id, so resolving the identity never asks a broker for the topic id.
        MaestroSignalHeaders.setMessageId(record.headers(), "evt-" + topic);
        listenerFor(ctx, topic).onMessage(record);
    }

    @SuppressWarnings("unchecked")
    private static MessageListener<String, byte[]> listenerFor(AssertableApplicationContext ctx, String topic) {
        assertThat(ctx).hasNotFailed();
        return ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().stream()
                .filter(c -> List.of(c.getContainerProperties().getTopics()).contains(topic))
                .map(c -> (MessageListener<String, byte[]>) c.getContainerProperties().getMessageListener())
                .findFirst()
                .orElseThrow();
    }

    /** One listener per parameter shape; each keeps what it received. */
    public static class TypedListeners {
        final AtomicReference<@Nullable List<Order>> list = new AtomicReference<>();
        final AtomicInteger listCalls = new AtomicInteger();
        final AtomicReference<@Nullable Map<String, List<Order>>> nested = new AtomicReference<>();
        final AtomicReference<@Nullable Order> plain = new AtomicReference<>();

        @MaestroSignalListener(topic = "orders.list", signalName = "list.signal")
        public SignalRouting onList(@Nullable List<Order> orders) {
            listCalls.incrementAndGet();
            list.set(orders);
            // Touch an element as a caller would: an erased read throws
            // ClassCastException here (LinkedHashMap cannot be cast to Order).
            if (orders != null && !orders.isEmpty()) {
                var first = orders.getFirst().id();
                assertThat(first).isNotNull();
            }
            return SignalRouting.builder().workflowId("wf-list").payload(orders).build();
        }

        @MaestroSignalListener(topic = "orders.nested", signalName = "nested.signal")
        public SignalRouting onNested(Map<String, List<Order>> byRegion) {
            nested.set(byRegion);
            return SignalRouting.builder().workflowId("wf-nested").payload(byRegion).build();
        }

        @MaestroSignalListener(topic = "orders.plain", signalName = "plain.signal")
        public SignalRouting onPlain(Order order) {
            plain.set(order);
            return SignalRouting.builder().workflowId("wf-" + order.id()).payload(order).build();
        }
    }

    /** The listener method lives on a generic superclass; only the subclass binds {@code E}. */
    public abstract static class TypedListener<E> {
        final AtomicReference<@Nullable Object> received = new AtomicReference<>();

        @MaestroSignalListener(topic = "orders.inherited", signalName = "inherited.signal")
        public SignalRouting onEvent(E event) {
            received.set(event);
            return route(event);
        }

        protected abstract SignalRouting route(E event);
    }

    public static class InheritedOrderListener extends TypedListener<Order> {
        @Override
        protected SignalRouting route(Order order) {   // the bridge casts: a LinkedHashMap would throw here
            return SignalRouting.builder().workflowId("wf-" + order.id()).payload(order).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean
        TypedListeners typedListeners() {
            return new TypedListeners();
        }

        @Bean
        InheritedOrderListener inheritedOrderListener() {
            return new InheritedOrderListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
