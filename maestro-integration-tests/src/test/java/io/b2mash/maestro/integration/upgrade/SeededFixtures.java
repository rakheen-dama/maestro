package io.b2mash.maestro.integration.upgrade;

import java.util.List;

/**
 * Every seeded-history fixture, oldest first: the list the shared upgrade
 * assertions ({@code SeededHistoryUpgradeIT}) run over. A release adds its
 * fixture here (RELEASING.md § Upgrade fixtures); a fixture is never removed.
 */
public final class SeededFixtures {

    private SeededFixtures() {
    }

    /** @return every fixture, oldest first */
    public static List<SeededFixture> all() {
        return List.of(Cbf5a2aFixture.INSTANCE, V030Rc1Fixture.INSTANCE, V030Rc2Fixture.INSTANCE);
    }
}
