package io.b2mash.maestro.core.engine;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

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
 * <p>A type with no variable for the owner to bind — a plain class, a
 * {@code List<Order>}, a method's own {@code <R> R}, or a variable the owner
 * leaves unbound (a raw supertype, the owner's own parameter) — is returned
 * exactly as declared, so it reads as it always has: an unbound variable as
 * its bound. Only a type the owner's hierarchy changes is returned as a
 * Jackson {@link JavaType} (which implements {@link Type}); render it with
 * {@link #typeName(Type)}.
 *
 * <p>Public but internal until 1.0: the starter and the Kafka listener use
 * it; workflow code never needs it.
 *
 * <p><b>Thread safety:</b> stateless; every method is thread-safe.
 */
public final class ResolvedTypes {

    /** Type construction only — no mapper settings are involved in resolving bindings. */
    private static final TypeFactory TYPES = TypeFactory.createDefaultInstance();

    private ResolvedTypes() {
    }

    /**
     * {@code method}'s generic return type as {@code owner} sees it.
     *
     * @param owner  the proxied interface, or the class whose instance the
     *               method is invoked on; must be {@code method}'s declaring
     *               type or a subtype of it
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
        return raw(returnType(owner, method), method.getReturnType());
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
        var resolved = new Class<?>[erased.length];
        for (int i = 0; i < erased.length; i++) {
            resolved[i] = raw(parameterType(owner, method, i), erased[i]);
        }
        return resolved;
    }

    /**
     * A type as a log line or exception message names it: Java's rendering
     * for a reflected type, the canonical form ({@code java.util.List<x.Order>})
     * for a resolved one.
     *
     * @param type a type from this class or from reflection
     * @return its name
     */
    public static String typeName(Type type) {
        return type instanceof JavaType resolved
                ? resolved.toCanonical().replace(",", ", ")
                : type.getTypeName();
    }

    private static Type resolve(Class<?> owner, Method method, Type declared) {
        if (!mentionsTypeVariable(declared) || owner == method.getDeclaringClass()) {
            // Nothing for the owner to bind: the method's own variables and
            // the declaring type's own parameters are unbound from there.
            return declared;
        }
        var self = TYPES.constructType(owner);
        var declaring = self.findSuperType(method.getDeclaringClass());
        if (declaring == null) {
            return declared;
        }
        var resolved = TYPES.resolveMemberType(declared, declaring.getBindings());
        // A variable the hierarchy leaves unbound (a raw supertype, a method's
        // own <R>) resolves to its bound, as the declared type reads: keep the
        // declared type so its reading and its rendering stay what they were.
        return resolved.equals(TYPES.constructType(declared)) ? declared : resolved;
    }

    private static Class<?> raw(Type type, Class<?> erased) {
        return type instanceof JavaType resolved ? resolved.getRawClass() : erased;
    }

    private static boolean mentionsTypeVariable(Type type) {
        return switch (type) {
            case Class<?> ignored -> false;
            case TypeVariable<?> ignored -> true;
            case ParameterizedType p -> Arrays.stream(p.getActualTypeArguments())
                    .anyMatch(ResolvedTypes::mentionsTypeVariable)
                    || (p.getOwnerType() != null && mentionsTypeVariable(p.getOwnerType()));
            case GenericArrayType a -> mentionsTypeVariable(a.getGenericComponentType());
            case WildcardType w -> Arrays.stream(w.getUpperBounds()).anyMatch(ResolvedTypes::mentionsTypeVariable)
                    || Arrays.stream(w.getLowerBounds()).anyMatch(ResolvedTypes::mentionsTypeVariable);
            default -> false;
        };
    }
}
