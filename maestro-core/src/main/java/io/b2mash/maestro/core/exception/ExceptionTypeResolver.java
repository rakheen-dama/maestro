package io.b2mash.maestro.core.exception;

import org.jspecify.annotations.Nullable;

/**
 * Resolves an exception class name read from persisted history, without
 * initialising the class.
 *
 * <p>A name stored in the event log is data, not trusted code: loading it with
 * initialisation would run an arbitrary class's static initialiser on every
 * replay pass. This helper is the single loader rule shared by replay
 * reconstruction and {@link ActivityExecutionException#causedBy(Class)}: try
 * the thread's context class loader (when set), then fall back to this
 * library's own loader; never initialise.
 *
 * <p>It lives in {@code core.exception} (not {@code core.engine}) so that the
 * exception package does not depend on the engine package. It is internal
 * engine API and carries no compatibility promise.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread.
 */
public final class ExceptionTypeResolver {

    private ExceptionTypeResolver() {
    }

    /**
     * Loads the named class without initialising it.
     *
     * @param name fully-qualified class name
     * @return the class, or {@code null} if neither loader can load it
     */
    public static @Nullable Class<?> resolve(String name) {
        var context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, false, context);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // fall through to the library loader
            }
        }
        try {
            return Class.forName(name, false, ExceptionTypeResolver.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
