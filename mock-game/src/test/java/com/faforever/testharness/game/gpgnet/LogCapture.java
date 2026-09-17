package com.faforever.testharness.game.gpgnet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures one class's log events for the length of a test, with that logger at DEBUG so the
 * assertion does not depend on the configured level. Closing it restores the level and detaches the
 * appender.
 *
 * <p>Events can arrive from other threads while a test reads them. {@code ListAppender} appends
 * while holding its own monitor, so {@link #events()} copies the list under that monitor.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previous;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogCapture(final Class<?> source) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = ctx.getLogger(source);
        previous = logger.getLevel();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    /** A snapshot of the events captured so far. */
    List<ILoggingEvent> events() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    /** Whether an event at {@code level} was captured whose message contains {@code fragment}. */
    boolean contains(final Level level, final String fragment) {
        return events().stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment));
    }

    @Override
    public void close() {
        logger.setLevel(previous);
        logger.detachAppender(appender);
        appender.stop();
    }
}
