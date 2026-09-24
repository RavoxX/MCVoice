package dev.mcvoice.client.log;

/** Where log lines go. Platforms bridge this to log4j/slf4j; tests use a list sink. */
public interface LogSink {
    enum Level { TRACE, DEBUG, INFO, WARN, ERROR }

    void log(Level level, Category category, String message, Throwable error);

    /** Fallback sink writing to stderr. */
    LogSink STDERR = new LogSink() {
        @Override
        public void log(Level level, Category category, String message, Throwable error) {
            System.err.println("[MCVoice/" + category + "] " + level + " " + message);
            if (error != null) {
                error.printStackTrace();
            }
        }
    };
}
