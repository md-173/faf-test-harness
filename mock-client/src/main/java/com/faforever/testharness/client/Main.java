package com.faforever.testharness.client;

import com.faforever.testharness.client.cli.ExitCodes;
import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintStream;
import java.util.Map;
import picocli.CommandLine;

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
        System.exit(run(args, System.getenv(), System.err));
    }

    /**
     * Runs the CLI and returns the process exit code instead of calling {@link System#exit(int)},
     * so the entry point's exit-code mapping stays unit-testable. Mirrors {@code
     * MockGameCli.parseOrReport} in the Mock Game.
     *
     * <p>{@link ConfigLoader#newCommandLine(String[], Map)} runs <em>before</em> {@link
     * CommandLine#execute(String...)} exists to catch anything, so a {@code --config} file that
     * cannot be read or parsed (and a stale {@code FAF_MOCK_CLIENT_*} env var) would otherwise
     * escape {@code main} as an uncaught exception: a stack trace and exit code {@code 1}, which is
     * not in the documented scheme. Catching it here and rendering it the way picocli renders a
     * parse failure keeps those inputs {@link ExitCodes#USAGE}, as {@code mock-client/README.md}
     * documents. Only construction is guarded — {@code execute} handles its own {@link
     * CommandLine.ParameterException}s.
     *
     * @param args raw command-line arguments
     * @param env environment map consulted by the layered default-value provider
     * @param err stream the construction-time diagnostic above is written and flushed to. It
     *     receives nothing else: once {@code execute} is entered, picocli writes parse errors,
     *     usage text and subcommand failures to its own writer, which defaults to {@link
     *     System#err} and is not redirected here.
     * @return the process exit code, always one of {@link ExitCodes}. An exception escaping a
     *     subcommand's {@code call()} would otherwise be picocli's {@code ExitCode.SOFTWARE}
     *     ({@code 1}); {@link com.faforever.testharness.client.cli.ExecutionExceptionHandler},
     *     installed by {@link ConfigLoader#newCommandLine(String[], Map)}, maps it to {@link
     *     ExitCodes#RUNTIME} instead
     */
    public static int run(
            final String[] args, final Map<String, String> env, final PrintStream err) {
        final CommandLine commandLine;
        try {
            commandLine = ConfigLoader.newCommandLine(args, env);
        } catch (CommandLine.ParameterException e) {
            err.println(e.getMessage());
            e.getCommandLine().usage(err);
            err.flush();
            return ExitCodes.USAGE;
        }
        return commandLine.execute(args);
    }
}
