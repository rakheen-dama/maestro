package io.b2mash.maestro.samples.perf;

import io.b2mash.maestro.samples.perf.web.StartRequest;
import io.b2mash.maestro.samples.perf.web.StartRequestValidator;
import io.b2mash.maestro.samples.perf.workflow.ChainInput;
import io.b2mash.maestro.samples.perf.workflow.HoldInput;
import io.b2mash.maestro.samples.perf.workflow.ParkInput;
import io.b2mash.maestro.samples.perf.workflow.SignalsInput;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One case per {@link StartRequestValidator} rule, each asserting the exact
 * error text the harness sees in a 400 body, plus the cases where omitted
 * knobs take their defaults (one per type).
 */
class StartRequestValidatorTest {

    private static final String ID_ERROR = "workflowId must match [A-Za-z0-9._-]{1,200}";
    private static final String TYPE_ERROR = "type must be chain, park, hold or signals";

    // ── workflowId ───────────────────────────────────────────────────────

    @Test
    void workflowIdMissing() {
        assertEquals(Optional.of(ID_ERROR), StartRequestValidator.validate(chain(null, null, null)));
    }

    @Test
    void workflowIdEmpty() {
        assertEquals(Optional.of(ID_ERROR), StartRequestValidator.validate(chain("", null, null)));
    }

    @Test
    void workflowIdWithForbiddenCharacter() {
        assertEquals(Optional.of(ID_ERROR), StartRequestValidator.validate(chain("run/1", null, null)));
    }

    @Test
    void workflowIdTooLong() {
        assertEquals(Optional.of(ID_ERROR), StartRequestValidator.validate(chain("a".repeat(201), null, null)));
    }

    @Test
    void workflowIdAtTheLimitAndEveryAllowedCharacter() {
        assertEquals(Optional.empty(), StartRequestValidator.validate(chain("a".repeat(200), null, null)));
        assertEquals(Optional.empty(), StartRequestValidator.validate(chain("Run_1.a-Z9", null, null)));
    }

    // ── type ─────────────────────────────────────────────────────────────

    @Test
    void typeMissing() {
        assertEquals(Optional.of(TYPE_ERROR),
                StartRequestValidator.validate(new StartRequest("r1", null, null, null, null, null, null, null)));
    }

    @Test
    void typeUnknown() {
        assertEquals(Optional.of(TYPE_ERROR),
                StartRequestValidator.validate(new StartRequest("r1", "fanout", null, null, null, null, null, null)));
    }

    // ── chain ────────────────────────────────────────────────────────────

    @Test
    void chainActivitiesBelowRange() {
        assertEquals(Optional.of("activities must be 1..50"),
                StartRequestValidator.validate(chain("r1", 0, null)));
    }

    @Test
    void chainActivitiesAboveRange() {
        assertEquals(Optional.of("activities must be 1..50"),
                StartRequestValidator.validate(chain("r1", 51, null)));
    }

    @Test
    void chainActivityMillisBelowRange() {
        assertEquals(Optional.of("activityMillis must be 0..10000"),
                StartRequestValidator.validate(chain("r1", null, -1L)));
    }

    @Test
    void chainActivityMillisAboveRange() {
        assertEquals(Optional.of("activityMillis must be 0..10000"),
                StartRequestValidator.validate(chain("r1", null, 10_001L)));
    }

    @Test
    void chainBoundsAreInclusive() {
        assertEquals(Optional.empty(), StartRequestValidator.validate(chain("r1", 1, 0L)));
        assertEquals(Optional.empty(), StartRequestValidator.validate(chain("r1", 50, 10_000L)));
    }

    @Test
    void chainDefaultsApplied() {
        var request = chain("r1", null, null);

        assertEquals(Optional.empty(), StartRequestValidator.validate(request));
        assertEquals(new ChainInput(5, 50), request.chainInput());
    }

    // ── park ─────────────────────────────────────────────────────────────

    @Test
    void parkModeUnknown() {
        assertEquals(Optional.of("parkMode must be signal or sleep"),
                StartRequestValidator.validate(park("r1", "nap", null)));
    }

    @Test
    void parkSecondsBelowRange() {
        assertEquals(Optional.of("parkSeconds must be 1..86400"),
                StartRequestValidator.validate(park("r1", null, 0L)));
    }

    @Test
    void parkSecondsAboveRange() {
        assertEquals(Optional.of("parkSeconds must be 1..86400"),
                StartRequestValidator.validate(park("r1", null, 86_401L)));
    }

    @Test
    void parkBoundsAreInclusiveAndBothModesAccepted() {
        assertEquals(Optional.empty(), StartRequestValidator.validate(park("r1", "signal", 1L)));
        assertEquals(Optional.empty(), StartRequestValidator.validate(park("r1", "sleep", 86_400L)));
    }

    @Test
    void parkDefaultsApplied() {
        var request = park("r1", null, null);

        assertEquals(Optional.empty(), StartRequestValidator.validate(request));
        assertEquals(new ParkInput("signal", 7200), request.parkInput());
    }

    // ── hold ─────────────────────────────────────────────────────────────

    @Test
    void holdActivitiesBelowRange() {
        assertEquals(Optional.of("activities must be 1..50"),
                StartRequestValidator.validate(hold("r1", 0, null)));
    }

    @Test
    void holdActivitiesAboveRange() {
        assertEquals(Optional.of("activities must be 1..50"),
                StartRequestValidator.validate(hold("r1", 51, null)));
    }

    @Test
    void holdMillisBelowRange() {
        assertEquals(Optional.of("holdMillis must be 0..20000"),
                StartRequestValidator.validate(hold("r1", null, -1L)));
    }

    @Test
    void holdMillisAboveRange() {
        assertEquals(Optional.of("holdMillis must be 0..20000"),
                StartRequestValidator.validate(hold("r1", null, 20_001L)));
    }

    @Test
    void holdBoundsAreInclusive() {
        assertEquals(Optional.empty(), StartRequestValidator.validate(hold("r1", 1, 0L)));
        assertEquals(Optional.empty(), StartRequestValidator.validate(hold("r1", 50, 20_000L)));
    }

    @Test
    void holdDefaultsApplied() {
        var request = hold("r1", null, null);

        assertEquals(Optional.empty(), StartRequestValidator.validate(request));
        assertEquals(new HoldInput(1, 1000), request.holdInput());
    }

    // ── signals ──────────────────────────────────────────────────────────

    @Test
    void signalsBelowRange() {
        assertEquals(Optional.of("signals must be 1..50"),
                StartRequestValidator.validate(signals("r1", 0, null)));
    }

    @Test
    void signalsAboveRange() {
        assertEquals(Optional.of("signals must be 1..50"),
                StartRequestValidator.validate(signals("r1", 51, null)));
    }

    @Test
    void signalsParkSecondsBelowRange() {
        assertEquals(Optional.of("parkSeconds must be 1..86400"),
                StartRequestValidator.validate(signals("r1", null, 0L)));
    }

    @Test
    void signalsParkSecondsAboveRange() {
        assertEquals(Optional.of("parkSeconds must be 1..86400"),
                StartRequestValidator.validate(signals("r1", null, 86_401L)));
    }

    @Test
    void signalsBoundsAreInclusive() {
        assertEquals(Optional.empty(), StartRequestValidator.validate(signals("r1", 1, 1L)));
        assertEquals(Optional.empty(), StartRequestValidator.validate(signals("r1", 50, 86_400L)));
    }

    @Test
    void signalsDefaultsApplied() {
        var request = signals("r1", null, null);

        assertEquals(Optional.empty(), StartRequestValidator.validate(request));
        assertEquals(new SignalsInput(5, 7200), request.signalsInput());
    }

    private static StartRequest chain(@Nullable String workflowId, @Nullable Integer activities,
                                      @Nullable Long activityMillis) {
        return new StartRequest(workflowId, "chain", activities, activityMillis, null, null, null, null);
    }

    private static StartRequest park(@Nullable String workflowId, @Nullable String parkMode,
                                     @Nullable Long parkSeconds) {
        return new StartRequest(workflowId, "park", null, null, parkMode, parkSeconds, null, null);
    }

    private static StartRequest hold(@Nullable String workflowId, @Nullable Integer activities,
                                     @Nullable Long holdMillis) {
        return new StartRequest(workflowId, "hold", activities, null, null, null, holdMillis, null);
    }

    private static StartRequest signals(@Nullable String workflowId, @Nullable Integer signals,
                                        @Nullable Long parkSeconds) {
        return new StartRequest(workflowId, "signals", null, null, null, parkSeconds, null, signals);
    }
}
