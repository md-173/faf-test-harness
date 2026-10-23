package com.faforever.testharness.shared.logging;

import org.slf4j.LoggerFactory;

/**
 * Child program for {@link UnusableLogFileEndToEndTest}. Configures logging as mock-client does,
 * emits one record, and shuts logging down so the JSONL file is flushed before the JVM exits.
 *
 * <p>mock-client configures twice: once from {@code ConfigLoader} and again from the subcommand,
 * which first sets {@code --log-file} back as the {@code LOG_FILE} property. This does the same
 * with the environment's value, so every case also pins that the second call repeats no notice
 * (#524).
 *
 * <p>The record is the point: it stands for the diagnostic a subcommand emits when it fails, which
 * an unusable {@code LOG_FILE} used to swallow along with the rest of logging.
 */
public final class UnusableLogFileChild {

    /** Message the parent looks for; stands in for a command's own failure diagnostic. */
    public static final String MARKER = "the command still got to say what went wrong";

    private UnusableLogFileChild() {}

    /**
     * Entry point.
     *
     * @param args unused
     */
    public static void main(final String[] args) {
        LoggingSetup.configure("MockClient");
        String logFile = System.getenv(LoggingSetup.LOG_FILE_ENV);
        if (logFile != null) {
            System.setProperty(LoggingSetup.LOG_FILE_ENV, logFile);
        }
        LoggingSetup.configure("MockClient");
        LoggerFactory.getLogger(UnusableLogFileChild.class).error(MARKER);
        LoggingSetup.shutdown();
    }
}
