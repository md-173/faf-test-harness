package com.faforever.testharness.client.state;

/**
 * What a finished session found: the verdicts {@code RunCommand} turns into {@code run}'s exit
 * code.
 *
 * <p>Each one is a judgement rather than a raw fact. An exit code or a failed call says what
 * happened; whether that was a fault or the harness's own doing is decided once, by {@link
 * MockClientLifecycle} or by {@link SessionFailures} on its behalf, in the same branch that logs
 * it, so the line and the exit code cannot disagree. This class only holds the answers. Only those
 * two write them, through the package-private recorders.
 *
 * <p>Read them once {@code stateReached(TERMINATED)} has completed. A verdict written inside the
 * transition action that drives TERMINATED, or inside TERMINATED's entry hook where teardown's
 * check writes, is ordered before that read: both run before {@code commitTransition} completes the
 * future {@code RunCommand} waits on, and {@code CompletableFuture.complete} happens-before the
 * {@code get} that returns. {@link #gameCrashed()} is the exception, written on a continuation; see
 * there. Every field is {@code volatile} for the reads that do not follow that future, such as a
 * test calling a getter directly.
 *
 * <p>It also holds one fact that is not a verdict, {@link #launchStarted()}, kept here rather than
 * in the lifecycle, whose length is budgeted.
 */
public final class SessionVerdicts {

    /** Backs {@link #launchFailed()}. */
    private volatile boolean launchFailed;

    /** Backs {@link #adapterLost()}. */
    private volatile boolean adapterLost;

    /** Backs {@link #gameCrashed()}. */
    private volatile boolean gameCrashed;

    /** Backs {@link #sessionFailed()}. */
    private volatile boolean sessionFailed;

    /** Backs {@link #launchStarted()}. */
    private volatile boolean launchStarted;

    /** Created by the lifecycle, which owns the only reference that can record. */
    SessionVerdicts() {}

    /**
     * Whether this session's game launch failed on the way up (WBS-3.1.3.3-fix, #437): the ICE
     * adapter or the game never came up.
     *
     * <p>Never recorded once {@code SessionTeardown} has started, though a signal can still set it,
     * since a terminal's SIGINT reaches the adapter too. That is why {@code RunCommand} names no
     * verdict on a signalled run.
     *
     * @return {@code true} if the adapter or game never came up before session teardown began
     */
    public boolean launchFailed() {
        return launchFailed;
    }

    /**
     * Whether this session's ICE adapter process died in a way nobody asked for (WBS-3.1.2.8-fix,
     * #406): a non-zero exit observed while the session was live, outside harness-initiated
     * teardown.
     *
     * <p>A boolean rather than the exit code, because {@link MockClientLifecycle#adapterExit()}
     * already exposes the code, and what a caller cannot get from the code alone is whether it was
     * a fault or the harness's own SIGTERM.
     *
     * <p>No argument about stale reads is needed on the route that matters. It is recorded inside
     * the {@code AdapterExited} transition action, which runs before TERMINATED's entry hook and
     * before the future {@code RunCommand} waits on completes. That ordering is the point of #406:
     * #357 read a verdict written on a continuation thread and exited 69 or 0 run to run. Reading
     * it earlier returns {@code false}, which is the right answer for an adapter that is still
     * running.
     *
     * <p>When another event the same death caused ends the session first, a call in flight failing
     * as the socket closes or the game exiting on its dead link, teardown's check records it
     * instead (#438; {@link SessionFailures#adapterAtTeardown}). That runs inside TERMINATED's
     * entry hook, which is ordered before the same read.
     *
     * @return {@code true} if the adapter's exit was classified as abnormal
     */
    public boolean adapterLost() {
        return adapterLost;
    }

    /**
     * Whether this session's game process died in a way nobody asked for (WBS-5.2): a non-zero exit
     * with no {@code GameEnded} observed and no harness-initiated teardown.
     *
     * <p>A boolean rather than the exit code, because {@link MockClientLifecycle#gameExit()}
     * already exposes the code. What a caller cannot get from the code alone is the judgement:
     * whether it was a fault or an expected consequence of the harness's own SIGTERM. The
     * lifecycle's {@code classifyGameExit} makes that call once, and this reports it.
     *
     * <p>Recorded on the game-exit completion handler and read on the main thread, so the two sides
     * need an ordering. On the {@code GameExited} route they have one: the record precedes the
     * event that completes the {@code stateReached(TERMINATED)} future. On the other routes into
     * TERMINATED, a lobby disconnect or the adapter exiting, the classification may simply not have
     * run yet, so the honest answer is {@code false}, and {@code volatile} makes that a defined
     * stale read rather than an undefined one. The window is narrow and benign: a lobby drop is
     * reported ahead of this anyway, and java-ice-adapter does not exit when the game dies (it
     * closes the game's connection, reports {@code Disconnected} over RPC and keeps accepting), so
     * a crashed game reaches TERMINATED through {@code GameExited} and nothing else.
     *
     * @return {@code true} if the game exit was classified as abnormal
     */
    public boolean gameCrashed() {
        return gameCrashed;
    }

    /**
     * Whether this session failed after its ICE adapter and game came up (WBS-3.1.3.3-fix, #445;
     * WBS-3.1.1.9-fix, #344): a {@code HostGame}, {@code JoinGame} or {@code ConnectToPeer} frame
     * it could not read, a {@code DisconnectFromPeer} one before the game started, an adapter that
     * answered a host, join or peer-connect call with an error or not within its timeout, or a
     * match the server cancelled after {@code game_launch} and before the game started.
     *
     * <p>Not recorded when one of those calls failed because the adapter's connection closed: the
     * connection is the adapter's finding, not the call's. Teardown's check decides it (#438, #452;
     * {@link SessionFailures#adapterAtTeardown}), recording this verdict for a live adapter whose
     * JSON-RPC link closed from its side, such as one whose stream stopped parsing, and {@link
     * #adapterLost()} for one that died. Otherwise never recorded once {@code SessionTeardown} has
     * started, the same rule as {@link #launchFailed()}.
     *
     * <p>Recorded inside the transition action that ends the session, or by teardown's check inside
     * TERMINATED's entry hook, both of which order it before {@code RunCommand}'s read. {@code
     * connectToPeer}'s asynchronous failure is the exception: it records the verdict and then posts
     * the {@code ShutdownRequested} that ends the session, which orders it the same way only when
     * that event is what ends it. If another route commits TERMINATED first, such as the server
     * closing the lobby or the game exiting, the verdict can land after the read, and a run with no
     * other finding exits {@code 0}. The window is the few instructions between the teardown check
     * and the record, which is why the record comes before the line that names it.
     *
     * @return {@code true} if the session failed after it came up, before session teardown began
     */
    public boolean sessionFailed() {
        return sessionFailed;
    }

    /** Records that the launch failed on the way up; see {@link #launchFailed()}. */
    void recordLaunchFailed() {
        launchFailed = true;
    }

    /** Records that the adapter died unaccounted for; see {@link #adapterLost()}. */
    void recordAdapterLost() {
        adapterLost = true;
    }

    /** Records that the game died unaccounted for; see {@link #gameCrashed()}. */
    void recordGameCrashed() {
        gameCrashed = true;
    }

    /** Records that the session failed after it came up; see {@link #sessionFailed()}. */
    void recordSessionFailed() {
        sessionFailed = true;
    }

    /**
     * Whether any verdict has been recorded. Read by teardown's adapter check ({@link
     * SessionFailures#adapterAtTeardown}), which must not add a second cause line; kept beside the
     * fields so a verdict added later is counted here too.
     *
     * @return {@code true} once any verdict has been recorded
     */
    boolean any() {
        return launchFailed || adapterLost || gameCrashed || sessionFailed;
    }

    /**
     * Whether the lifecycle started launching a game for a {@code game_launch} (WBS-3.1.2.6-fix,
     * #462), whether or not the launch came up. Not a verdict, so {@link #any()} leaves it out: it
     * decides whether teardown owes the lobby a {@code GameState Ended}, which the real client
     * sends after every outcome of a {@code game_launch}.
     *
     * @return {@code true} once a launch has started
     */
    boolean launchStarted() {
        return launchStarted;
    }

    /** Records that the lifecycle started a launch; see {@link #launchStarted()}. */
    void recordLaunchStarted() {
        launchStarted = true;
    }
}
