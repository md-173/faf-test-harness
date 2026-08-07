package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link InstanceConverter}'s resolution order. Events are captured from a throwaway
 * {@link LoggerContext} so the JVM-global logging used by the rest of the suite is untouched.
 *
 * <p>The {@code LoggerContext} fallback is the case that matters most in production. Logback's MDC
 * is a plain {@code ThreadLocal}, so subprocess capture threads and async workers never inherit the
 * value put by {@link LoggingSetup#configure} and would otherwise emit unlabelled records.
 */
final class InstanceConverterTest {

    /** Context-free converter under test; {@link InstanceConverter#resolve} is static. */
    private final InstanceConverter converter = new InstanceConverter();

    @AfterEach
    void clearMdc() {
        MDC.remove(LoggingSetup.INSTANCE_MDC_KEY);
    }

    /**
     * Logs one record on a throwaway context and returns the captured event.
     *
     * @param contextProperty instance label to store as a context property, or {@code null} for
     *     none
     * @return the single captured logging event
     */
    private static ILoggingEvent captureEvent(final String contextProperty) {
        LoggerContext context = new LoggerContext();
        // A hand-built context has no MDC adapter, and event construction dereferences it. Reuse
        // the global one so values put through the MDC facade above are visible here.
        context.setMDCAdapter(MDC.getMDCAdapter());
        context.start();
        if (contextProperty != null) {
            context.putProperty(LoggingSetup.INSTANCE_MDC_KEY, contextProperty);
        }
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        Logger logger = context.getLogger(InstanceConverterTest.class);
        logger.addAppender(appender);

        logger.info("record");

        context.stop();
        return appender.list.get(0);
    }

    @Test
    void resolvesFromMdcWhenSet() {
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "peer-a");

        assertEquals("peer-a", InstanceConverter.resolve(captureEvent(null)));
    }

    @Test
    void fallsBackToContextPropertyWhenMdcIsEmpty() {
        assertEquals("peer-b", InstanceConverter.resolve(captureEvent("peer-b")));
    }

    @Test
    void mdcWinsOverContextProperty() {
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "from-mdc");

        assertEquals("from-mdc", InstanceConverter.resolve(captureEvent("from-context")));
    }

    @Test
    void resolvesToEmptyStringWhenNoInstanceIsNamed() {
        assertEquals("", InstanceConverter.resolve(captureEvent(null)));
    }

    @Test
    void convertRendersBracketedSegmentWhenNamed() {
        assertEquals(" [peer-a]", converter.convert(captureEvent("peer-a")));
    }

    @Test
    void convertRendersNothingWhenUnnamed() {
        assertEquals("", converter.convert(captureEvent(null)));
    }
}
