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

    /**
     * A runtime failure after a subcommand started: one the subcommand reported itself, or any
     * exception that escaped its {@code call()} — see {@link ExecutionExceptionHandler}, which maps
     * the latter here so picocli's {@code ExitCode.SOFTWARE} ({@code 1}) is unreachable and this
     * scheme stays closed.
     */
    public static final int RUNTIME = 70;

    /**
     * The session ran, but the game process died in a way nobody asked for: a non-zero exit with no
     * {@code GameEnded} frame observed and no harness-initiated teardown (WBS-5.2).
     *
     * <p>That predicate is exactly the one {@code MockClientLifecycle.classifyGameExit} already
     * uses for its {@code mock-game exited abnormally} warning, and it is deliberately read from
     * there rather than re-derived here, so the log line and the exit code cannot disagree.
     *
     * <p><b>Wider than the word "crashed" suggests</b>, and intentionally. It covers a mock-game
     * that failed to start as well as one that died mid-match, because from the harness's side
     * those are the same finding: the game is gone and nothing accounted for it. Crash is this
     * codebase's existing word for that condition — {@code CrashRecoveryTest}, R41 "client game
     * crash recovery" — and the real client's {@code GameRunner.handleTermination} routes any
     * non-zero exit to {@code alertOnBadExit} in the same way.
     *
     * <p>Distinct from {@link #RUNTIME} because that code already means four other things here — a
     * failed token exchange, a lobby handshake timeout, a setup failure, an abrupt lobby close — so
     * reusing it would have told a pipeline nothing. {@code 71} sits next to it deliberately: this
     * is a runtime failure, and one with a known cause.
     *
     * <p>Before this existed the harness exited {@code 0} when its game died, reporting success for
     * a run that failed.
     */
    public static final int GAME_CRASHED = 71;

    private ExitCodes() {}
}
