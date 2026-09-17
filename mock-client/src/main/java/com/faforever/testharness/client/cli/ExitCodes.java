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
     * The session ran, but its game reported that the GPGNet link to the ICE adapter went down
     * underneath it: mock-game exited with its own {@code ADAPTER_LOST}, and no shutdown signal
     * explains it (WBS-5.2).
     *
     * <p>Keyed on a signal rather than on teardown having run, because teardown is what made the
     * old behaviour non-deterministic: an adapter dying drives TERMINATED and so teardown, racing
     * the game's exit classification. A Ctrl-C is marked before teardown quits the adapter, so a
     * game that dies of that is excluded without consulting anything that races.
     *
     * <p>Deliberately the same number as mock-game's {@code ExitCodes.ADAPTER_LOST}, unlike {@link
     * #GAME_CRASHED}, because here the two codes report the same event seen from two sides rather
     * than two different findings. {@code 69} is sysexits' {@code EX_UNAVAILABLE}, which fits both.
     *
     * <p>Separate from {@link #GAME_CRASHED} because the game's death is <em>accounted for</em>: it
     * said why it ended. Reporting it as a crash was also non-deterministic, since whether the
     * adapter's death drove teardown before the game's exit was classified decided between two exit
     * codes for one scenario.
     *
     * <p>One race remains, narrower than the one this replaced. If the adapter dies, the game is in
     * a footrace between noticing its own socket close and being terminated by the teardown that
     * the adapter's death triggers: winning reports this code, losing reports {@code 143} after
     * teardown and exits {@code OK}. The game is reacting to an EOF on a socket it is already
     * blocked reading, so it wins in practice, and closing the gap entirely would mean teardown
     * waiting on a process it is about to kill. What is gone is the previous coin flip between two
     * in-process callbacks, which no ordering of the harness's own work could decide.
     *
     * <p>Narrower than "the adapter died". It is reported only when the game noticed and said so.
     * An adapter that exits while the game is already being torn down still produces only the
     * {@code ICE adapter exited abnormally} warning; giving that its own exit status belongs to
     * adapter crash recovery (WBS-3.1.2.8) rather than here.
     */
    public static final int ADAPTER_LOST = 69;

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
     * <p>One exit is carved out of that width: a game reporting {@link #ADAPTER_LOST} told us why
     * it ended, so it is reported as that instead. See there for why.
     *
     * <p>Only that one, although mock-game also names its cause when it exits {@code LOBBY_TIMEOUT}
     * or {@code USAGE}. Those two stay here deliberately: a game that was never driven into a role,
     * or launched with an argument it rejected, is a failure of the harness run itself and worth
     * surfacing, whereas an adapter dying underneath a healthy game is not the game's doing.
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

    private ExitCodes() {}
}
