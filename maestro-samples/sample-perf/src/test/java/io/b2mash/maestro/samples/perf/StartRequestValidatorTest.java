package io.b2mash.maestro.samples.perf;

import io.b2mash.maestro.samples.perf.web.StartRequest;
import io.b2mash.maestro.samples.perf.web.StartRequestValidator;
import io.b2mash.maestro.samples.perf.workflow.ChainInput;
import io.b2mash.maestro.samples.perf.workflow.ParkInput;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One case per {@link StartRequestValidator} rule, each asserting the exact
 * error text the harness sees in a 400 body, plus the two cases where omitted
 * knobs take their defaults.
 */
class StartRequestValidatorTest {

    private static final String ID_ERROR = "workflowId must match [A-Za-z0-9._-]{1,200}";
    private static final String TYPE_ERROR = "type must be chain or park";

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
                StartRequestValidator.validate(new StartRequest("r1", null, null, null, null, null)));
    }

    @Test
    void typeUnknown() {
        assertEquals(Optional.of(TYPE_ERROR),
                StartRequestValidator.validate(new StartRequest("r1", "fanout", null, null, null, null)));
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

    private static StartRequest chain(@Nullable String workflowId, @Nullable Integer activities,
                                      @Nullable Long activityMillis) {
        return new StartRequest(workflowId, "chain", activities, activityMillis, null, null);
    }

    private static StartRequest park(@Nullable String workflowId, @Nullable String parkMode,
                                     @Nullable Long parkSeconds) {
        return new StartRequest(workflowId, "park", null, null, parkMode, parkSeconds);
    }
}
