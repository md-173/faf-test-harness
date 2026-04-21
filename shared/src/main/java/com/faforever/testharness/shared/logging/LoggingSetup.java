package com.faforever.testharness.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One-call logging initialiser for harness components.
 *
 * <p>Every component (MockClient, MockGame, ICEAdapter subprocess reader) calls {@link
 * #configure(String)} once at startup. This stamps every subsequent log record with the component
 * name, so interleaved output from concurrent processes remains distinguishable.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // In Main.main(), before any logger is created:
 * LoggingSetup.configure("MockClient");
 * Logger log = LoggerFactory.getLogger(MyClass.class);
 * log.info("Started");
 * // Console: [2026-04-17 12:00:00] [MockClient] [INFO ] Started
 * // File:    {"timestamp":"2026-04-17 12:00:00","component":"MockClient",...}
 * }</pre>
 *
 * <p>Log level is read from the {@value #LOG_LEVEL_ENV} environment variable (e.g. {@code
 * LOG_LEVEL=DEBUG}). The default is {@code INFO}.
 *
 * <p>The log file path is read from the {@value #LOG_FILE_ENV} environment variable. The default is
 * {@code logs/test-harness.jsonl}.
 */
public final class LoggingSetup {

    /** MDC key written into every log record to identify the source component. */
    public static final String COMPONENT_MDC_KEY = "component";

    /** Environment variable controlling the minimum log level for all components. */
    public static final String LOG_LEVEL_ENV = "LOG_LEVEL";

    /** Environment variable controlling the JSONL output file path. */
    public static final String LOG_FILE_ENV = "LOG_FILE";

    private LoggingSetup() {}

    /**
     * Configures logging for the named component.
     *
     * <p>Sets the SLF4J MDC {@value #COMPONENT_MDC_KEY} key so every subsequent log record is
     * tagged with {@code componentName}, then applies the {@value #LOG_LEVEL_ENV} environment
     * variable to the root logger.
     *
     * @param componentName label that appears in every log line, e.g. {@code "MockClient"} or
     *     {@code "MockGame"}
     */
    public static void configure(final String componentName) {
        MDC.put(COMPONENT_MDC_KEY, componentName);
        applyLogLevelFromEnv();
    }

    /**
     * Reads {@value #LOG_LEVEL_ENV} and applies it to the root Logback logger. No-ops if the
     * variable is absent or blank.
     */
    private static void applyLogLevelFromEnv() {
        String levelStr = System.getenv(LOG_LEVEL_ENV);
        if (levelStr == null || levelStr.isBlank()) {
            return;
        }
        Level level = Level.toLevel(levelStr, Level.INFO);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(level);
    }
}
