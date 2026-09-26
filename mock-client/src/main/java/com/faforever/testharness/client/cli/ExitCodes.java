package com.faforever.testharness.client.cli;

/**
 * Process exit codes returned by the Mock Client.
 *
 * <p>The values are stable so CI pipelines can distinguish failure modes without scraping log
 * output. {@link #USAGE} matches picocli's default ({@link picocli.CommandLine.ExitCode#USAGE}) so
 * parameter-exception handling does not need a custom remap.
 *
 * <p><b>The numbers are project-local, not sysexits.</b> {@link #USAGE} is picocli's {@code 2}
 * rather than sysexits' {@code EX_USAGE} ({@code 64}), and the codes past {@link #RUNTIME} are
 * assigned in sequence as failure modes earn one, so their sysexits meanings ({@code EX_OSERR} for
 * {@code 71}, {@code EX_OSFILE} for {@code 72}) say nothing about what they report here. Only
 * {@link #OK} and {@link #RUNTIME} agree with the standard ({@code EX_OK}, {@code EX_SOFTWARE}),
 * which is why mock-game's {@code ExitCodes} cites both sysexits and this class for its own {@code
 * 70}.
 *
 * <p>That class does follow sysexits, code by code, so <b>the two components' numbers are not
 * comparable</b>. A lost adapter is mock-game's {@code ADAPTER_LOST} ({@code 69}, {@code
 * EX_UNAVAILABLE}) when the game reports its own link going down, and this client's {@link
 * #ADAPTER_LOST} ({@code 72}) when the adapter process itself dies under a session. Same name,
 * different number, different observer: read each component against its own table.
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
     *
     * <p>For {@code run} that includes a session whose ICE adapter or game never came up
     * (WBS-3.1.3.3-fix, #437): a binary that could not be started, an adapter that exited or never
     * accepted its JSON-RPC connection, or one that refused a setup call. Read from {@code
     * SessionVerdicts.launchFailed()}. Before that existed, such a run exited {@code 0}. A {@code
     * game_launch} frame it could not read or use is one too (WBS-3.1.1.6-fix, #457): nothing came
     * up for it, and before that the run waited, until killed, for a launch it had dropped.
     *
     * <p>For {@code run} it also includes a login the lobby ended before {@code welcome}
     * (WBS-3.1.1.2-fix, #473), with {@code invalid} or a close, which ends the run at once rather
     * than after its 45 s setup timeout, and a lobby connection that dropped under a live session,
     * a drop without a Close frame (code 1006) included.
     *
     * <p>Deliberately not a code of its own. {@link #GAME_CRASHED} and {@link #ADAPTER_LOST} each
     * name a subprocess that died under a session that was running, and a launch that never came up
     * had no running session: like a failed token exchange or a handshake timeout, it never got one
     * going. {@code session} reports the same failure as {@code 70} at its {@code game_launch}
     * checkpoint, and {@code launch-ice} and {@code launch-game} already use it for a binary they
     * cannot start. The line logged ahead of the verdict names which of them it was.
     *
     * <p>For {@code run} it also includes a session that failed after its adapter and game came up
     * (WBS-3.1.3.3-fix, #445, and WBS-3.1.1.9-fix, #344): a {@code HostGame}, {@code JoinGame} or
     * {@code ConnectToPeer} frame it could not read, a {@code DisconnectFromPeer} one before the
     * game started, an adapter that answered a host, join or peer-connect call with an error or not
     * within its timeout, a match the server cancelled after {@code game_launch} and before the
     * game started, or an adapter still running once the session ended although its JSON-RPC link
     * had closed from its side, such as one whose stream stopped parsing (WBS-3.1.2.8-fix, #452).
     * Read from {@code SessionVerdicts.sessionFailed()}. That session did run, but no subprocess
     * died under it, which is what the two codes below are for. A call that failed because the
     * adapter's connection closed is not this by itself: teardown decides it, as {@link
     * #ADAPTER_LOST} when the adapter died (#438) and as this when it is still running without its
     * link.
     *
     * <p>An unexpected exception in the bring-up is a defect rather than a finding, and ends the
     * run here too instead of leaving it waiting (WBS-3.1.3.3-fix, #439): as a launch that never
     * came up when it is thrown in the launch, and as a failed session when it is thrown in the
     * host, join or peer-connect step.
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
     * process that exited before its match as well as one that died mid-match, because from the
     * harness's side those are the same finding: the game is gone and nothing accounted for it. A
     * binary that could not be started at all never becomes a process to classify; that is a launch
     * that never came up, {@link #RUNTIME}. Crash is this codebase's existing word for that
     * condition ({@code CrashRecoveryTest}, R41 "client game crash recovery"), and the real
     * client's {@code GameRunner.handleTermination} routes any non-zero exit to {@code
     * alertOnBadExit} in the same way.
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
     * <p>Distinct from {@link #RUNTIME}, which covers the ways a run fails without a subprocess
     * dying under a running session: a failed token exchange, a lobby handshake timeout, a setup
     * failure, an abrupt lobby close, a launch that never came up, a session that failed after it
     * came up. A game dying mid-session is the finding a pipeline most needs to tell from those,
     * which is why it has a number of its own rather than another share of {@code 70}. {@code 71}
     * sits next to it deliberately: this is a runtime failure, and one with a known cause.
     *
     * <p>Before this existed the harness exited {@code 0} when its game died, reporting success for
     * a run that failed.
     */
    public static final int GAME_CRASHED = 71;

    /**
     * The session ran, but the ICE adapter process died in a way nobody asked for: a non-zero exit
     * observed while the session was live, outside any harness-initiated teardown (WBS-3.1.2.8-fix,
     * #406).
     *
     * <p>Set where an exit has been judged abnormal, in the same step that logs {@code ICE adapter
     * exited abnormally}, so the log line and the exit code cannot disagree: {@code
     * MockClientLifecycle.onAdapterExited}, or teardown's own check of the adapter ({@code
     * SessionFailures.adapterAtTeardown}). Same arrangement {@link #GAME_CRASHED} has with {@code
     * mock-game exited abnormally}.
     *
     * <p><b>Keyed on the adapter's own exit, which is what makes it deterministic.</b> #357 tried
     * keying a client code on the game reporting mock-game's {@code ADAPTER_LOST}, and review
     * removed it: that classification runs on a {@code CompletableFuture} continuation, so {@code
     * RunCommand} could read the verdict before it was written and one scenario exited {@code 69}
     * or {@code 0} run to run. This flag is written inside the {@code AdapterExited} transition
     * action, or inside TERMINATED's entry hook by teardown's check, and both run before the {@code
     * stateReached(TERMINATED)} future that releases {@code RunCommand} is completed, so the read
     * is ordered with no future to await.
     *
     * <p>The two cover every way the session can end around a dying adapter (WBS-3.1.2.8-fix,
     * #438). When {@code AdapterExited} is the event that drives TERMINATED, which is what a killed
     * adapter usually produces, its action records this. When something the same death caused gets
     * there first, a {@code connectToPeer}, {@code hostGame} or {@code joinGame} call in flight
     * failing as the socket closes, or the game's own exit processed first, teardown finds the
     * adapter dead before it stops it, waiting up to two seconds for one whose link has just
     * closed, and records this instead. Never on a signalled run, whose signal kills the adapter
     * itself.
     *
     * <p><b>An adapter that was up and then died</b>, not one that never came up. A failed launch,
     * or an adapter that exits before its JSON-RPC port accepts a connection, fails the {@code
     * LaunchGame} transition into TERMINATED instead, and an adapter given a bad argument exits
     * {@code 0} while doing so (subprocess-orchestration-spec §2.6), so neither this flag nor its
     * exit code can speak for that case. That case is {@link #RUNTIME}, from {@code
     * SessionVerdicts.launchFailed()} (WBS-3.1.3.3-fix, #437).
     *
     * <p>An adapter that quits cleanly under its own power, exit {@code 0}, is not this either: it
     * reads as the real client's "terminated normally" and leaves this run's code alone.
     *
     * <p>Distinct from {@link #RUNTIME}, which covers the ways a run fails without a subprocess
     * dying under a running session (a failed token exchange, a handshake timeout, a setup failure,
     * an abrupt lobby close, a launch that never came up, a session that failed after it came up),
     * and from {@link #GAME_CRASHED}, which is the game dying rather than the adapter underneath
     * it. {@code 72} continues what those two started: a runtime failure, with a known cause.
     * Deliberately not mock-game's {@code 69}: the client logs that as a lost adapter link and it
     * contributes nothing to the run's code, so one number would mean two things in one log.
     *
     * <p>Before this existed the harness exited {@code 0} when its adapter died mid-session,
     * reporting success for a run that failed.
     */
    public static final int ADAPTER_LOST = 72;

    private ExitCodes() {}
}
