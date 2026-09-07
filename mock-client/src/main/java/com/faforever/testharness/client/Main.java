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
 *
 * <p><b>On a signal, the code below never reaches the process, and that is deliberate</b>
 * (WBS-3.1.3.2-fix, #296). {@code run} installs a JVM shutdown hook; a {@code SIGINT} or {@code
 * SIGTERM} runs it, the coordinated teardown closes the lobby, the resulting disconnect drives the
 * FSM to TERMINATED, and the main thread unblocks and reaches {@link System#exit(int)} <em>while
 * the shutdown sequence is already running</em>. {@link Runtime#exit(int)} does not return in that
 * state: it parks the calling thread until the JVM dies, and the JVM then exits with the signal's
 * own code — 130 for {@code SIGINT}, 143 for {@code SIGTERM}. Pinned by {@code
 * SignalExitCodeEndToEndTest}.
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
        int exitCode = ConfigLoader.newCommandLine(args, System.getenv()).execute(args);
        // Reached on every self-terminating path. On the signal path this parks the main thread
        // until the JVM dies with the signal's own code instead — see the class javadoc for why
        // that is accepted rather than worked around.
        System.exit(exitCode);
    }
}
