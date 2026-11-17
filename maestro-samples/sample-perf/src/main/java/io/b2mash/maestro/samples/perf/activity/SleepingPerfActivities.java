package io.b2mash.maestro.samples.perf.activity;

import org.springframework.stereotype.Component;

/**
 * {@link PerfActivities} that simulates work by sleeping. This is activity
 * code, not workflow code, so {@link Thread#sleep} is allowed here: it holds
 * an activity slot for {@code millis}, which is the load it models.
 *
 * <p>Thread-safe: stateless.
 */
@Component
public class SleepingPerfActivities implements PerfActivities {

    @Override
    public int step(int index, long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("step " + index + " interrupted while sleeping", e);
            }
        }
        return index + 1;
    }
}
