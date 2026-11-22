package io.b2mash.maestro.spring.config.inheritedfixture;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.WorkflowMethod;

import java.util.List;

/**
 * Issue 94 fixtures for the Spring path: an activity interface and a
 * {@code @WorkflowMethod} that inherit their methods from generic parents,
 * bound only by the subtype the starter sees.
 */
public final class InheritedFixtures {

    private InheritedFixtures() {
    }

    public record Order(String id) {
    }

    public record OrderRequest(String id) {
    }

    public interface Catalog<T> {
        T one(String id);

        List<T> all(String id);
    }

    @Activity
    public interface Orders extends Catalog<Order> {
    }

    public static final class OrdersImpl implements Orders {
        @Override
        public Order one(String id) {
            return new Order("order-" + id);
        }

        @Override
        public List<Order> all(String id) {
            return List.of(new Order("order-" + id));
        }
    }

    /** The entry point lives here; only the subclass binds {@code I}. */
    public abstract static class TypedWorkflow<I> {
        @WorkflowMethod
        public String run(I input) {
            return handle(input);   // the subclass's bridge casts: a LinkedHashMap would throw here
        }

        protected abstract String handle(I input);
    }
}
