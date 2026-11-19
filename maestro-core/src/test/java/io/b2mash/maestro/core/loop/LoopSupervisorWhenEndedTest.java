package io.b2mash.maestro.core.loop;

import io.b2mash.maestro.core.observe.EngineObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LoopSupervisor#whenEnded}: after the end is published, once, contained, and at once after the end. */
@Timeout(30)
class LoopSupervisorWhenEndedTest {

    @Test
    void aHookRunsOnceAfterTheEndIsPublishedAndALateOneRunsAtOnce() {
        var seen = new CopyOnWriteArrayList<String>();
        var supervisor = LoopSupervisor.start(LoopSpec.of("hooked", "maestro-hooked", LoopRole.HOUSEKEEPING,
                Duration.ofMillis(10)), new LoopBody() {
            @Override
            public Next iterate(LoopContext ctx) {
                return Next.afterPeriod();
            }

            @Override
            public void onExit() {
                seen.add("onExit");
            }
        }, EngineObserver.NOOP);
        var holder = new LoopSupervisor[]{supervisor};
        supervisor.whenEnded(() -> {
            throw new IllegalStateException("a throwing hook is contained (test)");
        });
        supervisor.whenEnded(() -> seen.add("hook saw " + holder[0].snapshot().state()));

        supervisor.requestStop();
        assertTrue(supervisor.awaitStopped(Duration.ofSeconds(5)));
        assertTrue(waitFor(() -> seen.size() == 2), String.valueOf(seen));
        assertEquals(List.of("onExit", "hook saw STOPPED"), List.copyOf(seen));

        supervisor.whenEnded(() -> seen.add("late"));
        assertEquals(List.of("onExit", "hook saw STOPPED", "late"), List.copyOf(seen), "a late hook runs at once");
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) {
        var until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }
}
