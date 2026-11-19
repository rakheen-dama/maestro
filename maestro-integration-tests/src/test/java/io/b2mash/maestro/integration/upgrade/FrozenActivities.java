package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.integration.support.MaestroEngineHarness;

import java.util.Map;

/**
 * One frozen fixture package's activity implementation ({@code SeedActivities})
 * and its invocation counter ({@code Invocations}), seen through types every
 * fixture shares. Each frozen package defines its own copies of those classes,
 * so a test written once for every fixture holds them through this view
 * ({@link SeededFixture#activities}).
 *
 * <h2>Thread Safety</h2>
 * <p>The counts are thread-safe (the frozen counter is); {@link #registerOn} is
 * called from the test thread.
 */
public interface FrozenActivities {

    /**
     * Registers the one implementation for every frozen activity interface on
     * {@code node}, replacing what was registered before.
     *
     * @param node a node of this build
     */
    void registerOn(MaestroEngineHarness node);

    /**
     * @param step a step key, {@code "<activity name>.<method>"}
     * @return how many times the step ran live
     * @throws IllegalArgumentException for a step the fixture does not define
     */
    int count(String step);

    /** @return every step key of the fixture with its count, zero included, sorted by key */
    Map<String, Integer> snapshot();
}
