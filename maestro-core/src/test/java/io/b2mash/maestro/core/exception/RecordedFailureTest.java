package io.b2mash.maestro.core.exception;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link RecordedFailure#details()} is isolated: it holds JSON-text node kinds only, and hands out copies. */
class RecordedFailureTest {

    @Test
    void detailsAreACopy() {
        var details = JsonNodeFactory.instance.objectNode().put("code", "51");
        var failure = new RecordedFailure("x.Y", "m", details);
        details.put("code", "changed");
        var read = failure.details();
        ((tools.jackson.databind.node.ObjectNode) read).put("code", "mutated");
        assertEquals("51", failure.details().get("code").stringValue());
        assertNotSame(failure.details(), failure.details());
    }

    @Test
    void aBinaryLeafIsRefusedBecauseACopyWouldShareItsArray() {
        var details = JsonNodeFactory.instance.objectNode();
        details.putArray("nested").add(JsonNodeFactory.instance.binaryNode(new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new RecordedFailure("x.Y", "m", details));
    }

    @Test
    void aPojoLeafIsRefused() {
        var details = JsonNodeFactory.instance.objectNode();
        details.set("pojo", JsonNodeFactory.instance.pojoNode(new StringBuilder("mutable")));
        assertThrows(IllegalArgumentException.class, () -> new RecordedFailure("x.Y", "m", details));
    }
}
