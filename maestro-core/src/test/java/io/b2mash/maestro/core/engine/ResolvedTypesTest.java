package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.TypeBindings;
import tools.jackson.databind.type.TypeFactory;
import tools.jackson.databind.type.TypeModifier;

import java.lang.reflect.Type;
import java.util.LinkedList;
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

    /** A method variable that shadows the class's {@code T} (PR #92 review finding 4). */
    interface Shadowing<T> {
        @SuppressWarnings("TypeParameterHidesVisibleType")
        <T extends Number> T call();

        List<T> all();
    }

    interface Strings extends Shadowing<String> { }

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

    @Test
    void aMethodVariableShadowingTheClassVariableIsNotBound() throws Exception {
        var call = Shadowing.class.getMethod("call");
        assertSame(call.getGenericReturnType(), ResolvedTypes.returnType(Strings.class, call));
        assertEquals(Number.class, ResolvedTypes.rawReturnType(Strings.class, call));
        assertEquals("java.util.List<java.lang.String>",
                ResolvedTypes.typeName(ResolvedTypes.returnType(Strings.class, Shadowing.class.getMethod("all"))));
    }

    /**
     * A host mapper's {@link TypeModifier} applies to a resolved type as it
     * does to a declared one (PR #92 review finding 3): here every
     * {@code List} reads as a {@code LinkedList}.
     */
    @Test
    void theConsumingMappersTypeModifiersApplyToAResolvedType() throws Exception {
        var mapper = JsonMapper.builder().addModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addTypeModifier(new TypeModifier() {
                    /** Constructing LinkedList resolves its List supertype: do not modify that. */
                    private final ThreadLocal<Boolean> inside = ThreadLocal.withInitial(() -> false);

                    @Override
                    public JavaType modifyType(JavaType type, Type jdkType, TypeBindings bindings,
                                               TypeFactory factory) {
                        if (type.getRawClass() != List.class || inside.get()) {
                            return type;
                        }
                        inside.set(true);
                        try {
                            return factory.constructCollectionType(LinkedList.class, type.getContentType());
                        } finally {
                            inside.set(false);
                        }
                    }
                });
            }
        }).build();
        var serializer = new PayloadSerializer(mapper);
        var stored = serializer.serialize(List.of(new Order("o-1")));

        var declared = serializer.deserialize(stored, Declared.class.getMethod("all").getGenericReturnType());
        var resolved = serializer.deserialize(stored, ResolvedTypes.returnType(Orders.class, Parent.class.getMethod("all")));

        assertEquals(LinkedList.class, declared.getClass(), "control: the modifier applies to a declared List<Order>");
        assertEquals(LinkedList.class, resolved.getClass());
        assertEquals(new Order("o-1"), ((List<?>) resolved).getFirst());
    }

    interface Declared {
        List<Order> all();
    }
}
