package io.b2mash.maestro.core.logcapture;

import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only SLF4J provider for {@code maestro-core}'s tests, which otherwise
 * have no SLF4J binding (every logger is a no-op): it lets a test capture what
 * a named logger emits — {@link #watch(Class)} — and drops everything else,
 * exactly as the no-op binding did. Registered through
 * {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}.
 *
 * <p>The MDC adapter is SLF4J's {@link NOPMDCAdapter}, the one the no-op
 * binding uses, so engine code that copies or restores the MDC behaves exactly
 * as it did before this provider existed.
 *
 * <p><b>Thread safety:</b> thread-safe; watches and records are concurrent
 * collections, and loggers are stateless.
 */
public final class CapturingSlf4jProvider implements SLF4JServiceProvider {

    /** One captured logging call: its level, its formatted message and its throwable. */
    public record CapturedLog(Level level, String message, @Nullable Throwable throwable) {}

    /** The records of one {@link #watch}; closing it stops the capture. */
    public static final class Capture implements AutoCloseable {
        private final String loggerName;
        private final List<CapturedLog> records = new CopyOnWriteArrayList<>();

        private Capture(String loggerName) {
            this.loggerName = loggerName;
        }

        /** Every call captured so far, in arrival order. */
        public List<CapturedLog> records() {
            return List.copyOf(records);
        }

        @Override
        public void close() {
            WATCHES.remove(loggerName, this);
        }
    }

    private static final Map<String, Capture> WATCHES = new ConcurrentHashMap<>();

    /**
     * Captures every call, at every level, of the logger named after
     * {@code type} until the returned capture is closed.
     *
     * @throws IllegalStateException if that logger is already watched
     */
    public static Capture watch(Class<?> type) {
        var capture = new Capture(type.getName());
        if (WATCHES.putIfAbsent(type.getName(), capture) != null) {
            throw new IllegalStateException("already watched: " + type.getName());
        }
        return capture;
    }

    private final ILoggerFactory loggerFactory = new ILoggerFactory() {
        private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

        @Override
        public Logger getLogger(String name) {
            return loggers.computeIfAbsent(name, CapturingLogger::new);
        }
    };
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
    }

    /** Enabled at every level while its name is watched, disabled otherwise. */
    private static final class CapturingLogger extends LegacyAbstractLogger {

        CapturingLogger(String name) {
            this.name = name;
        }

        private boolean watched() {
            return WATCHES.containsKey(name);
        }

        @Override public boolean isTraceEnabled() { return watched(); }
        @Override public boolean isDebugEnabled() { return watched(); }
        @Override public boolean isInfoEnabled() { return watched(); }
        @Override public boolean isWarnEnabled() { return watched(); }
        @Override public boolean isErrorEnabled() { return watched(); }

        @Override
        protected @Nullable String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, @Nullable Marker marker, String messagePattern,
                                                   Object @Nullable [] arguments, @Nullable Throwable throwable) {
            var capture = WATCHES.get(name);
            if (capture == null) {
                return;
            }
            var message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
            capture.records.add(new CapturedLog(level, message, throwable));
        }
    }
}
