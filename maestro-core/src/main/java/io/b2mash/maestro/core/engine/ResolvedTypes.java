package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A method's generic return and parameter types as a given owner type sees
 * them (Issue 94): a type variable of a generic supertype is replaced by the
 * type argument the owner's hierarchy binds to it.
 *
 * <p>Reflection reports a method by the type that declares it. A JDK proxy
 * of {@code interface Orders extends Parent<Order>} is invoked with
 * {@code Parent.load}, whose {@link Method#getGenericReturnType()} is the
 * variable {@code T}; read with that, a stored {@code Order} comes back as a
 * {@code LinkedHashMap}. Resolved against {@code Orders}, the return type is
 * {@code Order}. The same holds for a {@code @WorkflowMethod} or a
 * {@code @MaestroSignalListener} method inherited from a generic superclass,
 * resolved against the workflow or listener class.
 *
 * <p>The result is a plain reflective {@link Type} — the declared type with
 * the bound variables substituted — never a Jackson {@code JavaType}, so the
 * mapper that reads the payload constructs it itself and applies its own
 * {@code TypeModifier}s, exactly as for a declared type. Only variables the
 * <em>declaring type</em> declares are substituted, matched by declaration
 * and name: a method's own {@code <T>} that shadows the class's {@code T} is
 * never bound. A type with nothing to substitute — a plain class, a
 * {@code List<Order>}, a method's own {@code <R> R}, a variable the owner
 * leaves unbound (a raw supertype, the owner's own parameter) — is returned
 * as the very object reflection declared, so it reads as it always has: an
 * unbound variable as its bound.
 *
 * <p>Public but internal until 1.0: the starter and the Kafka listener use
 * it; workflow code never needs it.
 *
 * <p><b>Thread safety:</b> stateless; every method is thread-safe, and the
 * types it returns are immutable.
 */
public final class ResolvedTypes {

    private ResolvedTypes() {
    }

    /**
     * {@code method}'s generic return type as {@code owner} sees it.
     *
     * @param owner  the proxied interface, or the class whose instance the
     *               method is invoked on; {@code method}'s declaring type or a
     *               subtype of it
     * @param method a method of {@code owner} (declared or inherited)
     * @return the resolved type, or {@link Method#getGenericReturnType()}
     *         itself when {@code owner} binds nothing in it
     */
    public static Type returnType(Class<?> owner, Method method) {
        return resolve(owner, method, method.getGenericReturnType());
    }

    /**
     * {@code method}'s generic parameter type at {@code index} as
     * {@code owner} sees it.
     *
     * @param owner  as for {@link #returnType}
     * @param method a method of {@code owner} (declared or inherited)
     * @param index  the parameter's position
     * @return the resolved type, or the declared generic parameter type itself
     *         when {@code owner} binds nothing in it
     * @throws IndexOutOfBoundsException if {@code method} has no such parameter
     */
    public static Type parameterType(Class<?> owner, Method method, int index) {
        return resolve(owner, method, method.getGenericParameterTypes()[index]);
    }

    /**
     * The erased class of {@link #returnType}: {@code Order} for
     * {@code T load()} inherited by {@code Orders extends Parent<Order>},
     * {@link Method#getReturnType()} when nothing is bound.
     *
     * @param owner  as for {@link #returnType}
     * @param method a method of {@code owner}
     * @return the resolved raw return class
     */
    public static Class<?> rawReturnType(Class<?> owner, Method method) {
        var type = returnType(owner, method);
        return type == method.getGenericReturnType() ? method.getReturnType() : rawClass(type);
    }

    /**
     * The erased classes of every {@link #parameterType}.
     *
     * @param owner  as for {@link #returnType}
     * @param method a method of {@code owner}
     * @return the resolved raw parameter classes, in order
     */
    public static Class<?>[] rawParameterTypes(Class<?> owner, Method method) {
        var erased = method.getParameterTypes();
        var declared = method.getGenericParameterTypes();
        var resolved = new Class<?>[erased.length];
        for (int i = 0; i < erased.length; i++) {
            var type = parameterType(owner, method, i);
            resolved[i] = type == declared[i] ? erased[i] : rawClass(type);
        }
        return resolved;
    }

    /**
     * A type as a log line or exception message names it
     * ({@link Type#getTypeName()}: {@code java.util.List<x.Order>}).
     *
     * @param type a type from this class or from reflection
     * @return its name
     */
    public static String typeName(Type type) {
        return type.getTypeName();
    }

    /**
     * The erasure of a substituted type (one that holds no unbound variable
     * where it was substituted).
     */
    static Class<?> rawClass(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            case GenericArrayType a -> Array.newInstance(rawClass(a.getGenericComponentType()), 0).getClass();
            case TypeVariable<?> v -> rawClass(v.getBounds()[0]);
            case WildcardType w -> rawClass(w.getUpperBounds()[0]);
            default -> Object.class;
        };
    }

    private static Type resolve(Class<?> owner, Method method, Type declared) {
        var declaring = method.getDeclaringClass();
        if (owner == declaring || declaring.getTypeParameters().length == 0) {
            // Nothing for the owner to bind: the method's own variables and
            // the declaring type's own parameters are unbound from there.
            return declared;
        }
        var bindings = new HashMap<TypeVariable<?>, Type>();
        collect(owner, bindings);
        return substitute(declared, bindings);
    }

    /**
     * Binds the type parameters of every generic supertype of {@code type}
     * to the arguments its hierarchy passes, each argument already expressed
     * in {@code type}'s own terms. Keys are {@link TypeVariable}s, whose
     * equality is declaration plus name, so a method's variable never matches.
     */
    private static void collect(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        var supertypes = new ArrayList<Type>(Arrays.asList(type.getGenericInterfaces()));
        if (type.getGenericSuperclass() != null) {
            supertypes.add(type.getGenericSuperclass());
        }
        for (var supertype : supertypes) {
            Class<?> raw;
            if (supertype instanceof ParameterizedType p) {
                raw = (Class<?>) p.getRawType();
                var parameters = raw.getTypeParameters();
                var arguments = p.getActualTypeArguments();
                for (int i = 0; i < parameters.length; i++) {
                    bindings.putIfAbsent(parameters[i], substitute(arguments[i], bindings));
                }
            } else if (supertype instanceof Class<?> c) {
                raw = c;
            } else {
                continue;
            }
            collect(raw, bindings);
        }
    }

    /** {@code type} with every bound variable replaced; the same object when nothing was. */
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case Class<?> c -> c;
            case TypeVariable<?> v -> bindings.getOrDefault(v, v);
            case ParameterizedType p -> {
                var arguments = p.getActualTypeArguments();
                var substituted = substituteAll(arguments, bindings);
                var ownerType = p.getOwnerType();
                var substitutedOwner = ownerType == null ? null : substitute(ownerType, bindings);
                yield substituted == arguments && substitutedOwner == ownerType
                        ? p
                        : new Parameterized((Class<?>) p.getRawType(), substituted, substitutedOwner);
            }
            case GenericArrayType a -> {
                var component = a.getGenericComponentType();
                var substituted = substitute(component, bindings);
                if (substituted == component) {
                    yield a;
                }
                yield substituted instanceof Class<?> c
                        ? Array.newInstance(c, 0).getClass()
                        : new GenericArray(substituted);
            }
            case WildcardType w -> {
                var upperBounds = w.getUpperBounds();
                var lowerBounds = w.getLowerBounds();
                var upper = substituteAll(upperBounds, bindings);
                var lower = substituteAll(lowerBounds, bindings);
                yield upper == upperBounds && lower == lowerBounds ? w : new Wildcard(upper, lower);
            }
            default -> type;
        };
    }

    /** The same array when no element changed. */
    private static Type[] substituteAll(Type[] types, Map<TypeVariable<?>, Type> bindings) {
        Type[] result = types;
        for (int i = 0; i < types.length; i++) {
            var substituted = substitute(types[i], bindings);
            if (substituted != types[i]) {
                if (result == types) {
                    result = types.clone();
                }
                result[i] = substituted;
            }
        }
        return result;
    }

    // ── Reflective types, equal to the JDK's own for the same structure ──

    private record Parameterized(Class<?> raw, Type[] arguments, @Nullable Type owner)
            implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public @Nullable Type getOwnerType() {
            return owner;
        }

        @Override
        public String getTypeName() {
            return raw.getName() + Arrays.stream(arguments).map(Type::getTypeName)
                    .collect(Collectors.joining(", ", "<", ">"));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType p && raw.equals(p.getRawType())
                    && Objects.equals(owner, p.getOwnerType())
                    && Arrays.equals(arguments, p.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }

    private record GenericArray(Type component) implements GenericArrayType {
        @Override
        public Type getGenericComponentType() {
            return component;
        }

        @Override
        public String getTypeName() {
            return component.getTypeName() + "[]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GenericArrayType a && component.equals(a.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return component.hashCode();
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }

    private record Wildcard(Type[] upper, Type[] lower) implements WildcardType {
        @Override
        public Type[] getUpperBounds() {
            return upper.clone();
        }

        @Override
        public Type[] getLowerBounds() {
            return lower.clone();
        }

        @Override
        public String getTypeName() {
            if (lower.length > 0) {
                return "? super " + lower[0].getTypeName();
            }
            return upper.length == 0 || upper[0] == Object.class ? "?" : "? extends " + upper[0].getTypeName();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WildcardType w && Arrays.equals(upper, w.getUpperBounds())
                    && Arrays.equals(lower, w.getLowerBounds());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(upper) ^ Arrays.hashCode(lower);
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }
}
