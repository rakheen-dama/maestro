package io.b2mash.maestro.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowContextSurfaceTest {

    @Test
    void engineMutatorsAreNotPublicOnWorkflowContext() {
        for (var name : List.of("nextSequence", "setSequence", "setReplaying")) {
            assertTrue(Arrays.stream(WorkflowContext.class.getMethods()).noneMatch(m -> m.getName().equals(name)),
                    name + " must not be a public method of WorkflowContext");
        }
    }

    @Test
    void internalsRoundTrip() {
        var ctx = new WorkflowContext(UUID.randomUUID(), "wf", UUID.randomUUID(), "t", "q", "svc", 0, true, null);
        assertEquals(1, WorkflowContextInternals.nextSequence(ctx));
        WorkflowContextInternals.setSequence(ctx, 41);
        assertEquals(42, WorkflowContextInternals.nextSequence(ctx));
        WorkflowContextInternals.setReplaying(ctx, false);
        assertFalse(ctx.isReplaying());
    }
}
