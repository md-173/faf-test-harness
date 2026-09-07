package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.Main;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * Child program for {@link LogLevelFlagEndToEndTest}. Runs the real entry point with the arguments
 * it is given, then emits one {@code DEBUG} record and shuts logging down so the JSONL file is
 * flushed before the JVM exits.
 *
 * <p>It exists because Logback resolves {@code ${LOG_LEVEL:-INFO}} exactly once per process, when
 * the first logger is created. Whether {@code --log-level} took effect is therefore a property of a
 * whole JVM's startup ordering and cannot be observed in-process: by the time a test method runs,
 * some earlier test has already configured Logback, and forcing the root level programmatically —
 * which is the only in-process option — bypasses the very mechanism under test.
 *
 * <p>The record is emitted by this class rather than harvested from the command, because no
 * subcommand has a {@code DEBUG} statement on a path that fails fast enough to keep the child
 * cheap. Its only job is to answer "is DEBUG enabled in this process, after the entry point ran".
 */
public final class LogLevelFlagChild {

    /** Message the parent test looks for; present only when {@code DEBUG} is enabled. */
    public static final String DEBUG_MARKER = "debug records are enabled";

    private LogLevelFlagChild() {}

    /**
     * Entry point.
     *
     * @param args command-line arguments forwarded verbatim to {@link Main#run}
     */
    public static void main(final String[] args) {
        int exitCode = Main.run(args, Map.of(), System.err);
        LoggerFactory.getLogger(LogLevelFlagChild.class).debug(DEBUG_MARKER);
        LoggingSetup.shutdown();
        System.exit(exitCode);
    }
}
