package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ResolvedTypes} (Issue 94): a type variable the owner's hierarchy
 * binds is replaced by its argument; anything the owner does not bind is
 * returned exactly as declared.
 */
class ResolvedTypesTest {

    record Order(String id) { }

    interface Parent<T> {
        T load();

        List<T> all();

        Map<String, List<T>> byKey();

        void save(T value, String note);

        <R> R call();

        String plain();
    }

    interface Mid<T> extends Parent<T> { }

    interface Orders extends Mid<Order> { }

    @SuppressWarnings("rawtypes")
    interface RawOrders extends Parent { }

    @Test
    void aBoundVariableResolvesThroughATwoLevelChain() throws Exception {
        var load = Parent.class.getMethod("load");
        assertEquals(Order.class, ResolvedTypes.rawReturnType(Orders.class, load));
        assertEquals(ResolvedTypesTest.class.getName() + "$Order",
                ResolvedTypes.typeName(ResolvedTypes.returnType(Orders.class, load)));
    }

    @Test
    void containersKeepTheBoundElementType() throws Exception {
        assertEquals("java.util.List<" + Order.class.getName() + ">",
                ResolvedTypes.typeName(ResolvedTypes.returnType(Orders.class, Parent.class.getMethod("all"))));
        assertEquals("java.util.Map<java.lang.String, java.util.List<" + Order.class.getName() + ">>",
                ResolvedTypes.typeName(ResolvedTypes.returnType(Orders.class, Parent.class.getMethod("byKey"))));
    }

    @Test
    void parametersResolveToo() throws Exception {
        var save = Parent.class.getMethod("save", Object.class, String.class);
        assertArrayEquals(new Class<?>[]{Order.class, String.class}, ResolvedTypes.rawParameterTypes(Orders.class, save));
        assertSame(save.getGenericParameterTypes()[1], ResolvedTypes.parameterType(Orders.class, save, 1));
    }

    @Test
    void whatTheOwnerDoesNotBindIsReturnedAsDeclared() throws Exception {
        var call = Parent.class.getMethod("call");
        var load = Parent.class.getMethod("load");
        var plain = Parent.class.getMethod("plain");
        assertSame(call.getGenericReturnType(), ResolvedTypes.returnType(Orders.class, call), "a method's own <R>");
        assertEquals("R", ResolvedTypes.typeName(ResolvedTypes.returnType(Orders.class, call)));
        assertSame(load.getGenericReturnType(), ResolvedTypes.returnType(Parent.class, load), "the declaring type");
        assertSame(load.getGenericReturnType(), ResolvedTypes.returnType(RawOrders.class, load), "a raw supertype");
        assertEquals(Object.class, ResolvedTypes.rawReturnType(RawOrders.class, load));
        assertSame(plain.getGenericReturnType(), ResolvedTypes.returnType(Orders.class, plain), "no variable");
    }
}
