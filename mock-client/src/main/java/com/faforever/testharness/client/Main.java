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
 *
 * <p><b>On a signal, the code below never reaches the process, and that is deliberate</b>
 * (WBS-3.1.3.2-fix, #296). {@code run} installs a JVM shutdown hook; a {@code SIGINT} or {@code
 * SIGTERM} runs it, the coordinated teardown closes the lobby, the resulting disconnect drives the
 * FSM to TERMINATED, and the main thread unblocks — often, though not always, reaching {@link
 * System#exit(int)} <em>while the shutdown sequence is already running</em>. The exit code is 143
 * either way; which thread gets there first is incidental and must not be relied on. In particular,
 * anything added between {@code execute} and {@code System.exit} — flushing a report, say —
 * frequently will not run on this path. {@link Runtime#exit(int)} does not return in that state: it
 * parks the calling thread until the JVM dies, and the JVM then exits with the signal's own code —
 * 130 for {@code SIGINT}, 143 for {@code SIGTERM}. The JDK behaviour behind this is pinned by
 * {@code SignalExitCodeEndToEndTest}, which exercises those semantics directly rather than through
 * this class.
 *
 * <p>That is the right outcome and the one {@code mock-client/README.md} documents, so this accepts
 * it rather than working around it. The alternative — capturing the computed code and calling
 * {@link Runtime#halt(int)} from the hook — would deliver a harness code at the cost of skipping
 * every shutdown hook that had not finished, {@code SubprocessRegistry}'s included, so a run
 * interrupted mid-session could leave an orphaned adapter or game behind. A tidier number is not
 * worth an orphaned subprocess.
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
     * @return the exit code this method computes, always one of {@link ExitCodes} — but the process
     *     reports it only when this method is what ended the run; a SIGINT/SIGTERM supersedes it
     *     with 130/143 (see the class javadoc). An exception escaping a subcommand's {@code call()}
     *     would otherwise be picocli's {@code ExitCode.SOFTWARE} ({@code 1}); {@link
     *     com.faforever.testharness.client.cli.ExecutionExceptionHandler}, installed by {@link
     *     ConfigLoader#newCommandLine(String[], Map)}, maps it to {@link ExitCodes#RUNTIME} instead
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
