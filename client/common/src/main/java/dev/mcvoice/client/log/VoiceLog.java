package dev.mcvoice.client.log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Category-based logger. Per-frame logging only happens at TRACE, which must be
 * explicitly enabled; {@link #every} rate-limits repetitive warnings.
 */
public final class VoiceLog {
    private static volatile LogSink sink = LogSink.STDERR;
    private static volatile LogSink.Level threshold = LogSink.Level.INFO;
    private static volatile boolean positionLogging = false;
    private static final ConcurrentHashMap<String, Long> LAST = new ConcurrentHashMap<String, Long>();

    private VoiceLog() {
    }

    public static void setSink(LogSink s) {
        sink = s == null ? LogSink.STDERR : s;
    }

    public static void setThreshold(LogSink.Level level) {
        threshold = level;
    }

    /** Exact positions are only logged when this is enabled AND the level is DEBUG or lower. */
    public static void setPositionLogging(boolean enabled) {
        positionLogging = enabled;
    }

    public static boolean positionLoggingEnabled() {
        return positionLogging && enabled(LogSink.Level.DEBUG);
    }

    public static boolean enabled(LogSink.Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void log(LogSink.Level level, Category c, String msg, Throwable t) {
        if (enabled(level)) {
            try {
                sink.log(level, c, msg, t);
            } catch (RuntimeException ignored) {
                // logging must never break voice or the game
            }
        }
    }

    public static void trace(Category c, String msg) { log(LogSink.Level.TRACE, c, msg, null); }
    public static void debug(Category c, String msg) { log(LogSink.Level.DEBUG, c, msg, null); }
    public static void info(Category c, String msg) { log(LogSink.Level.INFO, c, msg, null); }
    public static void warn(Category c, String msg) { log(LogSink.Level.WARN, c, msg, null); }
    public static void warn(Category c, String msg, Throwable t) { log(LogSink.Level.WARN, c, msg, t); }
    public static void error(Category c, String msg, Throwable t) { log(LogSink.Level.ERROR, c, msg, t); }

    /** Log at most once per {@code intervalMs} for the given key. */
    public static void every(long intervalMs, String key, LogSink.Level level, Category c, String msg) {
        if (!enabled(level)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST.get(key);
        if (last == null || now - last >= intervalMs) {
            LAST.put(key, now);
            log(level, c, msg, null);
        }
    }
}
