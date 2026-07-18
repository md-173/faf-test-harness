package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LoggingSetup#shutdown(LoggerContext)} against a throwaway {@link LoggerContext},
 * so the flush/stop behaviour is exercised without tearing down the JVM-global logging used by the
 * rest of the suite.
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
}
