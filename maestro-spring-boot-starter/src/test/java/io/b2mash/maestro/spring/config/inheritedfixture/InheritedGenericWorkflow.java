package io.b2mash.maestro.spring.config.inheritedfixture;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.spring.config.inheritedfixture.InheritedFixtures.Order;
import io.b2mash.maestro.spring.config.inheritedfixture.InheritedFixtures.OrderRequest;
import io.b2mash.maestro.spring.config.inheritedfixture.InheritedFixtures.Orders;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads two inherited-generic activity results as {@code Order}s, records
 * what it saw (live run first, then each replay), parks on {@code "go"}.
 */
@DurableWorkflow(name = "inherited-generic")
public class InheritedGenericWorkflow extends InheritedFixtures.TypedWorkflow<OrderRequest> {

    /** What each run saw, in order. */
    public static final List<String> SEEN = new CopyOnWriteArrayList<>();

    @ActivityStub(startToCloseTimeout = "PT10S")
    private Orders orders;

    @Override
    protected String handle(OrderRequest request) {
        Order one = orders.one(request.id());
        Order first = orders.all(request.id()).getFirst();
        var seen = request.id() + ">" + one.id() + "/" + first.id();
        SEEN.add(seen);
        WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(5));
        return seen;
    }
}
