package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue 94 through the test kit: an activity interface that inherits its
 * methods from a generic parent ({@code Orders extends Catalog<Order>})
 * hands workflow code {@code Order}s — scalar, inside a {@code List} and
 * inside a {@code Map} — on the first run and on the replay after a crash,
 * and an inherited {@code @Compensate} receives the typed result.
 */
class InheritedGenericActivityTest {

    private TestWorkflowEnvironment env;
    private RecordingOrderBooking booking;
    private final RecordingReservations reservations = new RecordingReservations();
    private final RecordingPlainReservations plainReservations = new RecordingPlainReservations();

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        booking = new RecordingOrderBooking();
        env.registerActivities(Orders.class, new OrdersImpl());
        env.registerActivities(OrderBooking.class, booking);
        env.registerActivities(Reservations.class, reservations);
        env.registerActivities(PlainReservations.class, plainReservations);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void inheritedGenericResults_areTypedOnTheFirstRunAndAfterACrash() throws Exception {
        var crash = env.crashAfter(3);
        var handle = env.startWorkflow(CatalogWorkflow.class, "a");

        assertEquals(1, crash.recover());

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(10)));
        assertEquals("order-a/order-a/order-a", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertEquals(List.of("Orders.one", "Orders.all", "Orders.byKey"), handle.replayedSteps());
    }

    @Test
    void inheritedCompensate_namingTheChildsMethod_receivesTheTypedResultAfterACrash() throws Exception {
        var crash = env.crashAfter(1);
        var handle = env.startWorkflow(BookingWorkflow.class, "b");

        crash.recover();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));
        assertEquals(List.of("OrderBooking.book"), handle.replayedSteps());
        assertEquals(List.of(new Order("order-b")), booking.cancelled, "the compensation got an Order");
    }

    /**
     * PR #92 review finding 2: {@code undo(String)} accepts both the
     * activity's argument and its result. A non-generic interface has always
     * passed the result (a single parameter assignable from the return type
     * is tried first); an inherited {@code T reserve(String)} with
     * {@code T = String} now selects the same, where its erased {@code Object}
     * return made rc.1–rc.4 pass the original argument.
     */
    @Test
    void anInheritedGenericCompensation_selectsItsArgumentsAsTheNonGenericInterfaceDoes() throws Exception {
        var inherited = env.startWorkflow(ReservationWorkflow.class, "order-1");
        var plain = env.startWorkflow(PlainReservationWorkflow.class, "order-1");

        assertEquals(WorkflowStatus.FAILED, inherited.awaitTerminal(Duration.ofSeconds(10)));
        assertEquals(WorkflowStatus.FAILED, plain.awaitTerminal(Duration.ofSeconds(10)));
        assertEquals(List.of("reservation-order-1"), plainReservations.undone, "the non-generic selection");
        assertEquals(plainReservations.undone, reservations.undone, "the inherited generic selects the same");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface Reserving<T> {
        @Compensate("undo")
        T reserve(String orderId);
    }

    @Activity
    public interface Reservations extends Reserving<String> {
        void undo(String value);

        void fail(String orderId);
    }

    @Activity
    public interface PlainReservations {
        @Compensate("undo")
        String reserve(String orderId);

        void undo(String value);

        void fail(String orderId);
    }

    static final class RecordingReservations implements Reservations {
        final List<String> undone = new CopyOnWriteArrayList<>();

        @Override public String reserve(String orderId) { return "reservation-" + orderId; }
        @Override public void undo(String value) { undone.add(value); }
        @Override public void fail(String orderId) { throw new IllegalStateException("declined " + orderId); }
    }

    static final class RecordingPlainReservations implements PlainReservations {
        final List<String> undone = new CopyOnWriteArrayList<>();

        @Override public String reserve(String orderId) { return "reservation-" + orderId; }
        @Override public void undo(String value) { undone.add(value); }
        @Override public void fail(String orderId) { throw new IllegalStateException("declined " + orderId); }
    }

    @DurableWorkflow(name = "inherited-reservation")
    public static class ReservationWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private Reservations reservations;

        @WorkflowMethod
        @Saga
        public String run(String orderId) {
            reservations.reserve(orderId);
            reservations.fail(orderId);
            return "unreachable";
        }
    }

    @DurableWorkflow(name = "plain-reservation")
    public static class PlainReservationWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private PlainReservations reservations;

        @WorkflowMethod
        @Saga
        public String run(String orderId) {
            reservations.reserve(orderId);
            reservations.fail(orderId);
            return "unreachable";
        }
    }

    public record Order(String id) {
    }

    public interface Catalog<T> {
        T one(String id);

        List<T> all(String id);

        Map<String, List<T>> byKey(String id);
    }

    @Activity
    public interface Orders extends Catalog<Order> {
    }

    static final class OrdersImpl implements Orders {
        @Override
        public Order one(String id) {
            return new Order("order-" + id);
        }

        @Override
        public List<Order> all(String id) {
            return List.of(new Order("order-" + id));
        }

        @Override
        public Map<String, List<Order>> byKey(String id) {
            return Map.of(id, List.of(new Order("order-" + id)));
        }
    }

    @DurableWorkflow(name = "inherited-catalog")
    public static class CatalogWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Orders orders;

        @WorkflowMethod
        public String run(String id) {
            Order one = orders.one(id);
            Order first = orders.all(id).getFirst();
            Order keyed = orders.byKey(id).get(id).getFirst();
            return one.id() + "/" + first.id() + "/" + keyed.id();
        }
    }

    public interface Booking<T> {
        @Compensate("cancel")
        T book(String id);
    }

    /** The compensation is declared only on the child, typed as the child binds {@code T}. */
    @Activity
    public interface OrderBooking extends Booking<Order> {
        void cancel(Order order);

        String ship(String id);
    }

    static final class RecordingOrderBooking implements OrderBooking {
        final List<Order> cancelled = new CopyOnWriteArrayList<>();

        @Override
        public Order book(String id) {
            return new Order("order-" + id);
        }

        @Override
        public void cancel(Order order) {
            cancelled.add(order);
        }

        @Override
        public String ship(String id) {
            throw new IllegalStateException("no courier for " + id);
        }
    }

    @DurableWorkflow(name = "inherited-booking")
    public static class BookingWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private OrderBooking booking;

        @WorkflowMethod
        @Saga
        public String run(String id) {
            booking.book(id);
            return booking.ship(id);
        }
    }
}
