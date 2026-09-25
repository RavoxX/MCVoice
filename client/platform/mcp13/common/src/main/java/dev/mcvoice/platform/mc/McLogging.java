package dev.mcvoice.platform.mc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.LogSink;

/** Bridges MCVoice log categories to the game's logging (Log4j). */
public final class McLogging implements LogSink {
    private static final Logger LOG = LogManager.getLogger("MCVoice");

    @Override
    public void log(Level level, Category category, String message, Throwable error) {
        String m = "[" + category + "] " + message;
        switch (level) {
            case TRACE:
                LOG.trace(m, error);
                break;
            case DEBUG:
                LOG.debug(m, error);
                break;
            case INFO:
                LOG.info(m, error);
                break;
            case WARN:
                LOG.warn(m, error);
                break;
            default:
                LOG.error(m, error);
        }
    }
}
