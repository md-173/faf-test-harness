package com.faforever.testharness.client;

import com.faforever.testharness.client.config.ConfigLoader;

/**
 * Mock Client process entry point.
 *
 * <p>Builds a picocli {@link picocli.CommandLine} via {@link ConfigLoader#newCommandLine(String[],
 * java.util.Map)} and delegates to {@link picocli.CommandLine#execute(String...)}. Picocli walks
 * the subcommand tree, runs the matching {@code Callable.call()}, and returns its exit code. All
 * config validation, help/version handling, and per-subcommand logic lives downstream — this class
 * deliberately holds no business logic.
 *
 * <p>Exit codes are defined in {@link com.faforever.testharness.client.cli.ExitCodes}. See {@code
 * mock-client/README.md} for the full reference table.
 */
public final class Main {

    private Main() {}

    /**
     * Entry point.
     *
     * @param args command-line arguments forwarded to picocli
     */
    public static void main(final String[] args) {
        int exitCode = ConfigLoader.newCommandLine(args, System.getenv()).execute(args);
        System.exit(exitCode);
    }
}
