package dev.mcvoice.platform.mc;

//#if MC >= 1.18
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//#else
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//#endif

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.LogSink;

/** Bridges MCVoice log categories to the game's logging (SLF4J from 1.18, Log4j before). */
public final class McLogging implements LogSink {
    //#if MC >= 1.18
    private static final Logger LOG = LoggerFactory.getLogger("MCVoice");
    //#else
    private static final Logger LOG = LogManager.getLogger("MCVoice");
    //#endif

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
