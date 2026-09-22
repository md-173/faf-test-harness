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
     * codebase's existing word for that condition ({@code CrashRecoveryTest}, R41 "client game
     * crash recovery"), and the real client's {@code GameRunner.handleTermination} routes any
     * non-zero exit to {@code alertOnBadExit} in the same way.
     *
     * <p>One exit is carved out of that width: mock-game's own {@code ADAPTER_LOST} ({@code 69}).
     * The game told us why it ended, and an adapter dying underneath a healthy game is not the
     * game's doing, so it is logged as a lost link and contributes nothing here. Adapter death has
     * a code of its own, {@link #ADAPTER_LOST}, keyed on the adapter's own exit rather than on this
     * one: this classification is asynchronous, after the adapter's death may already have released
     * {@code RunCommand}, so a code read from it would differ run to run.
     *
     * <p>Only that one, although mock-game also names its cause when it exits {@code LOBBY_TIMEOUT}
     * or {@code USAGE}. Those two stay here deliberately: a game that was never driven into a role,
     * or launched with an argument it rejected, is a failure of the harness run itself and worth
     * surfacing.
     *
     * <p>Distinct from {@link #RUNTIME} because that code already means four other things here: a
     * failed token exchange, a lobby handshake timeout, a setup failure, an abrupt lobby close. So
     * reusing it would have told a pipeline nothing. {@code 71} sits next to it deliberately: this
     * is a runtime failure, and one with a known cause.
     *
     * <p>Before this existed the harness exited {@code 0} when its game died, reporting success for
     * a run that failed.
     */
    public static final int GAME_CRASHED = 71;

    /**
     * The session ran, but the ICE adapter process died in a way nobody asked for: a non-zero exit
     * observed while the session was live, outside any harness-initiated teardown (WBS-3.1.2.8).
     *
     * <p>Set where {@code MockClientLifecycle.onAdapterExited} has already decided an exit was
     * abnormal, in the same branch that logs {@code ICE adapter exited abnormally}, so the log line
     * and the exit code cannot disagree. Same arrangement {@link #GAME_CRASHED} has with {@code
     * mock-game exited abnormally}.
     *
     * <p><b>Keyed on the adapter's own exit, which is what makes it deterministic.</b> #357 tried
     * keying a client code on the game reporting mock-game's {@code ADAPTER_LOST}, and review
     * removed it: that classification runs on a {@code CompletableFuture} continuation, so {@code
     * RunCommand} could read the verdict before it was written and one scenario exited {@code 69}
     * or {@code 0} run to run. This flag is written inside the {@code AdapterExited} transition
     * action, which runs before TERMINATED's entry hook and before the {@code
     * stateReached(TERMINATED)} future that releases {@code RunCommand} is completed, so the read
     * is ordered with no future to await.
     *
     * <p>The guarantee is exactly that, and no wider: ordered whenever {@code AdapterExited} is the
     * event that drives TERMINATED, which is what a killed adapter produces. Verified live under
     * both SIGKILL and SIGTERM against the pinned adapter, which registers no shutdown hook and so
     * closes its GPGNet socket only as its process exits, leaving the game unable to report the
     * lost link first. A session ended by some other event while the adapter is dying reports what
     * that event found instead.
     *
     * <p><b>An adapter that was up and then died</b>, not one that never came up. A failed launch,
     * or an adapter that exits before its JSON-RPC port accepts a connection, fails the {@code
     * LaunchGame} transition into TERMINATED instead, and an adapter given a bad argument exits
     * {@code 0} while doing so (subprocess-orchestration-spec §2.6), so neither this flag nor its
     * exit code can speak for that case. It is carded separately.
     *
     * <p>An adapter that quits cleanly under its own power, exit {@code 0}, is not this either: it
     * reads as the real client's "terminated normally" and leaves this run's code alone.
     *
     * <p>Distinct from {@link #RUNTIME}, which already means a failed token exchange, a handshake
     * timeout, a setup failure or an abrupt lobby close, and from {@link #GAME_CRASHED}, which is
     * the game dying rather than the adapter underneath it. {@code 72} continues what those two
     * started: a runtime failure, with a known cause. Deliberately not mock-game's {@code 69}: the
     * client logs that as a lost adapter link and it leaves the code at {@link #OK}, so one number
     * would mean two things in one log.
     *
     * <p>Before this existed the harness exited {@code 0} when its adapter died mid-session,
     * reporting success for a run that failed.
     */
    public static final int ADAPTER_LOST = 72;

    private ExitCodes() {}
}
