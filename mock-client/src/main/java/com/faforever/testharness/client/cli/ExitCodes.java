package com.faforever.testharness.client.cli;

/**
 * Process exit codes returned by the Mock Client.
 *
 * <p>The values are stable so CI pipelines can distinguish failure modes without scraping log
 * output. {@link #USAGE} matches picocli's default ({@link picocli.CommandLine.ExitCode#USAGE}) so
 * parameter-exception handling does not need a custom remap.
 */
public final class ExitCodes {

    /** Successful run. */
    public static final int OK = 0;

    /**
     * Bad invocation : invalid args, missing required options, unknown subcommand, no subcommand
     * given, unreadable config file, malformed JSON, bad URI, bad port. Aligned with picocli's
     * default parameter-exception exit code.
     */
    public static final int USAGE = 2;

    /** Reserved for runtime failures once subcommands ship. */
    public static final int RUNTIME = 70;

    private ExitCodes() {}
}
