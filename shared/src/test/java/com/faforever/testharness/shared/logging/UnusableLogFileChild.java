package com.faforever.testharness.shared.logging;

import org.slf4j.LoggerFactory;

/**
 * Child program for {@link UnusableLogFileEndToEndTest}. Configures logging exactly as a real
 * component's {@code Main} does, emits one record, and shuts logging down so the JSONL file is
 * flushed before the JVM exits.
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
        LoggerFactory.getLogger(UnusableLogFileChild.class).error(MARKER);
        LoggingSetup.shutdown();
    }
}
