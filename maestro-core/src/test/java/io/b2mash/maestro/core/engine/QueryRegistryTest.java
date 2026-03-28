package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.QueryMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryRegistry}.
 */
class QueryRegistryTest {

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry();
    }

    // ── Registration and lookup ───────────────────────────────────────

    @Test
    @DisplayName("Register workflow with @QueryMethod → lookup succeeds")
    void registerAndLookup() {
        registry.register("OrderWorkflow", WorkflowWithQuery.class);

        var method = registry.getQueryMethod("OrderWorkflow", "getStatus");
        assertTrue(method.isPresent());
        assertEquals("getStatus", method.get().getName());
    }

    @Test
    @DisplayName("Custom name via @QueryMethod(name = '...') → lookup by custom name")
    void customQueryName() {
        registry.register("OrderWorkflow", WorkflowWithCustomName.class);

        // Should be found by custom name, not method name
        assertTrue(registry.getQueryMethod("OrderWorkflow", "progress").isPresent());
        assertFalse(registry.getQueryMethod("OrderWorkflow", "getItemsProcessed").isPresent());
    }

    @Test
    @DisplayName("Multiple query methods per workflow → all registered")
    void multipleQueryMethods() {
        registry.register("OrderWorkflow", WorkflowWithMultipleQueries.class);

        var names = registry.getQueryNames("OrderWorkflow");
        assertEquals(2, names.size());
        assertTrue(names.contains("getStatus"));
        assertTrue(names.contains("getCount"));
    }

    @Test
    @DisplayName("Query method with one parameter → registers successfully")
    void queryMethodWithParameter() {
        registry.register("OrderWorkflow", WorkflowWithParameterQuery.class);

        var method = registry.getQueryMethod("OrderWorkflow", "getItemByIndex");
        assertTrue(method.isPresent());
        assertEquals(1, method.get().getParameterCount());
    }

    // ── Lookup misses ─────────────────────────────────────────────────

    @Test
    @DisplayName("Lookup non-existent query name → empty")
    void lookupMissingQueryName() {
        registry.register("OrderWorkflow", WorkflowWithQuery.class);

        assertFalse(registry.getQueryMethod("OrderWorkflow", "nonExistent").isPresent());
    }

    @Test
    @DisplayName("Lookup non-existent workflow type → empty")
    void lookupMissingWorkflowType() {
        assertFalse(registry.getQueryMethod("UnknownWorkflow", "getStatus").isPresent());
    }

    @Test
    @DisplayName("No query methods → getQueryNames returns empty set")
    void noQueryMethods() {
        registry.register("PlainWorkflow", WorkflowWithoutQueries.class);

        assertTrue(registry.getQueryNames("PlainWorkflow").isEmpty());
    }

    @Test
    @DisplayName("Unregistered workflow type → getQueryNames returns empty set")
    void unregisteredWorkflowType() {
        assertTrue(registry.getQueryNames("UnknownWorkflow").isEmpty());
    }

    // ── Validation failures ───────────────────────────────────────────

    @Test
    @DisplayName("Void return type → fails at registration")
    void voidReturnTypeRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("BadWorkflow", WorkflowWithVoidQuery.class));
        assertTrue(ex.getMessage().contains("non-void return type"));
    }

    @Test
    @DisplayName("Static method → fails at registration")
    void staticMethodRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("BadWorkflow", WorkflowWithStaticQuery.class));
        assertTrue(ex.getMessage().contains("must not be static"));
    }

    @Test
    @DisplayName("Too many parameters → fails at registration")
    void tooManyParametersRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("BadWorkflow", WorkflowWithTooManyParams.class));
        assertTrue(ex.getMessage().contains("0 or 1 parameter"));
    }

    @Test
    @DisplayName("Double registration of same workflow type → fails")
    void doubleRegistrationRejected() {
        registry.register("OrderWorkflow", WorkflowWithQuery.class);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("OrderWorkflow", WorkflowWithQuery.class));
        assertTrue(ex.getMessage().contains("already registered"));
    }

    @Test
    @DisplayName("Duplicate query name → fails at registration")
    void duplicateQueryNameRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("BadWorkflow", WorkflowWithDuplicateNames.class));
        assertTrue(ex.getMessage().contains("Duplicate @QueryMethod name"));
    }

    // ── Test workflow classes ─────────────────────────────────────────

    public static class WorkflowWithQuery {
        @QueryMethod
        public String getStatus() {
            return "running";
        }
    }

    public static class WorkflowWithCustomName {
        @QueryMethod(name = "progress")
        public int getItemsProcessed() {
            return 42;
        }
    }

    public static class WorkflowWithMultipleQueries {
        @QueryMethod
        public String getStatus() {
            return "running";
        }

        @QueryMethod
        public int getCount() {
            return 10;
        }
    }

    public static class WorkflowWithParameterQuery {
        @QueryMethod
        public String getItemByIndex(int index) {
            return "item-" + index;
        }
    }

    public static class WorkflowWithoutQueries {
        public String run() {
            return "done";
        }
    }

    public static class WorkflowWithVoidQuery {
        @QueryMethod
        public void badQuery() {
            // void return not allowed
        }
    }

    public static class WorkflowWithStaticQuery {
        @QueryMethod
        public static String badQuery() {
            return "bad";
        }
    }

    public static class WorkflowWithTooManyParams {
        @QueryMethod
        public String badQuery(String a, String b) {
            return a + b;
        }
    }

    public static class WorkflowWithDuplicateNames {
        @QueryMethod(name = "status")
        public String getStatus() {
            return "running";
        }

        @QueryMethod(name = "status")
        public String getStatusAlternative() {
            return "running";
        }
    }
}
