package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.Main;
import java.util.Map;

/**
 * Child program for {@link LogLevelFlagEndToEndTest}. Runs the real entry point and exits, emitting
 * no records of its own and never touching {@code LoggingSetup}.
 *
 * <p>That silence is the whole point. This child is used to assert that an invocation creates
 * <em>no</em> log file, so it must not create one itself — and merely resolving a logger would,
 * because that is what initialises Logback and opens the appender. {@link LogLevelFlagChild} logs a
 * marker and {@link EarlyFailureChild} calls {@code LoggingSetup.shutdown}, so both would create
 * the file they were being used to prove absent.
 */
public final class SilentEntryPointChild {

    private SilentEntryPointChild() {}

    /**
     * Entry point.
     *
     * @param args command-line arguments forwarded verbatim to {@link Main#run}
     */
    public static void main(final String[] args) {
        System.exit(Main.run(args, Map.of(), System.err));
    }
}
