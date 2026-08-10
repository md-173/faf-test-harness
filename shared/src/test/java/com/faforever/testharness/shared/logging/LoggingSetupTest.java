package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LoggingSetup#shutdown(LoggerContext)} against a throwaway {@link LoggerContext},
 * so the flush/stop behaviour is exercised without tearing down the JVM-global logging used by the
 * rest of the suite, and for the instance-label resolution added by WBS-3.1.6.2.
 *
 * <p>Only the system-property source of {@value LoggingSetup#INSTANCE_NAME_ENV} is exercised. A
 * process cannot set its own environment variables in Java, so the environment fallback is covered
 * by inspection rather than by a test.
 */
final class LoggingSetupTest {

    @Test
    void shutdownStopsTheContext() {
        LoggerContext context = new LoggerContext();
        context.start();
        assertTrue(context.isStarted(), "sanity: a started context reports started");

        LoggingSetup.shutdown(context);

        assertFalse(context.isStarted(), "shutdown() should stop (flush/close) the context");
    }

    @Test
    void shutdownIsIdempotent() {
        LoggerContext context = new LoggerContext();
        context.start();

        LoggingSetup.shutdown(context);
        assertDoesNotThrow(() -> LoggingSetup.shutdown(context), "a second shutdown must be safe");
        assertFalse(context.isStarted());
    }

    @AfterEach
    void clearInstanceProperty() {
        System.clearProperty(LoggingSetup.INSTANCE_NAME_ENV);
    }

    @Test
    void instanceNameResolvesFromSystemProperty() {
        System.setProperty(LoggingSetup.INSTANCE_NAME_ENV, "peer-a");

        assertEquals("peer-a", LoggingSetup.resolveInstanceName());
    }

    @Test
    void instanceNameIsTrimmed() {
        System.setProperty(LoggingSetup.INSTANCE_NAME_ENV, "  peer-a  ");

        assertEquals("peer-a", LoggingSetup.resolveInstanceName());
    }

    @Test
    void blankInstanceNameCountsAsUnset() {
        System.setProperty(LoggingSetup.INSTANCE_NAME_ENV, "   ");

        assertEquals(
                "",
                LoggingSetup.resolveInstanceName(),
                "a blank label must not stamp an empty instance field");
    }

    @Test
    void unsetInstanceNameResolvesToEmptyString() {
        assertEquals("", LoggingSetup.resolveInstanceName());
    }
}
