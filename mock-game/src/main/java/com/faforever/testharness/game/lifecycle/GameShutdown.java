package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.net.GameTrafficSession;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mock game's single, idempotent shutdown sequence (WBS-3.2.5.2). Runs four steps, in order:
 *
 * <ol>
 *   <li>stop the lifecycle's own scheduling ({@link MockGameLifecycle#stopSchedules()}), used for
 *       launch delays and timed match durations, so those tasks stop firing transitions;
 *   <li>close the {@link GpgNetConnection} — closing the socket <em>is</em> the shutdown protocol;
 *       no farewell frame is sent;
 *   <li>stop the lifecycle FSM's time-based scheduling ({@link StateMachine#cancel()}), so no
 *       timeout queued <em>on the FSM itself</em> fires a transition mid-teardown;
 *   <li>close the {@link GameTrafficSession} (WBS-4.3.2), which stops the peer traffic cadence and
 *       closes the shared lobby socket, ending the receiver's loop.
 * </ol>
 *
 * <p>{@link MockGameLifecycle} uses two separate schedulers: the internal {@link StateMachine} one
 * for timeouts and another for launch-delay and match-duration tasks. This is why there are two
 * very similar steps. Step 3 cancels the StateMachine's own timer while step 1 cancels the other
 * MockGameLifecycle scheduler. They are not adjacent, and the split is the subject of the next
 * paragraph.
 *
 * <p><b>The close sits between them because it is the step that can unblock {@link
 * StateMachine#cancel()}.</b> Every outbound frame is written from a transition action inside the
 * synchronized {@link
 * StateMachine#receiveEvent(com.faforever.testharness.shared.statemachine.Event)}, and {@link
 * GpgNetConnection#send} is a blocking write. If the adapter stops reading, the kernel send buffer
 * fills and the FSM thread blocks in that write still holding the StateMachine monitor — so {@code
 * cancel()}, which needs that monitor, would wait behind it forever while the one action that would
 * break the stall, closing the socket, sat behind the wait. That is not hypothetical: in the pinned
 * faf-ice-adapter, {@code GPGNetServer$GPGNetClient.listenerThread} calls {@code
 * processGpgnetMessage} inline in its read loop, which reaches {@code
 * RPCService.onGpgNetMessageReceived} and then {@code getPeerOrWait}, an untimed {@code
 * CompletableFuture.get()} on the first JSON-RPC peer. Until a peer attaches, the adapter accepts
 * this game's connection and then stops reading it with the socket still open. Closing before
 * {@code cancel()} turns the stalled write into an immediate {@code IOException}, the action fails
 * into ENDED, and the monitor is released. Reversing steps two and three reinstates the hang
 * (WBS-3.2.5.2 / #299).
 *
 * <p><b>Step one stays ahead of the close because it does not need that monitor.</b> {@link
 * MockGameLifecycle#stopSchedules()} is a bare {@code shutdownNow()} on a plain scheduler; it never
 * touches the StateMachine, so it cannot be the step that stalls and it keeps the drain-first
 * property WBS-2.3.11 added below. Only {@code cancel()} had to move.
 *
 * <p><b>Closing before {@code cancel()} is only safe because a local close is not news to the
 * FSM.</b> The risk is confined to one of {@link GpgNetConnection#close()}'s two dispatch paths.
 * With a live socket the disconnect is delivered on the reader thread, which cannot hold up
 * teardown whatever it does; but on a connection that never opened its socket, {@code close()}
 * fires the listener <em>synchronously on the calling thread</em>. {@code
 * MockGameLifecycle.setupStateMachine} filters {@code LOCAL_CLOSE} at the source rather than
 * posting it, so that synchronous call returns without touching the FSM. Were it ever to post an
 * event instead, this step would take the StateMachine monitor and block behind the very stall it
 * exists to break — the same defect, moved one line down. That filter is therefore a precondition
 * of this ordering and not merely a log-noise fix, which is how it is described at its own call
 * site.
 *
 * <p><b>The cost: an FSM timeout can now fire between steps two and three.</b> Scoping what is
 * actually left in that window: {@link MockGameLifecycle} calls {@link
 * StateMachine#setTimeout(long, com.faforever.testharness.shared.statemachine.State,
 * com.faforever.testharness.shared.statemachine.TransitionAction)} exactly once, for the GPGNet
 * connect timeout, and every committed transition cancels and clears all pending timeouts — so the
 * FSM's own timer can only fire here while the machine is still in INITIALIZING. That timeout
 * writes nothing; it assigns SERVER_NOT_CONNECTED and targets ENDED, whose entry hook is this
 * once-guarded sequence, so it converges where teardown was already going and the re-entrant {@link
 * #run()} returns on the guard. This is a property of {@link MockGameLifecycle}'s call sites rather
 * than of {@link StateMachine}, so it has to be re-checked if a second {@code setTimeout} is ever
 * added.
 *
 * <p>One refinement to that scoping: the timeout list is not yet cleared at the instant this runs
 * from ENDED's entry hook, because {@code Transition.transition} fires {@code to.entry()} before
 * {@code receiveEvent} commits. Harmless, because the FSM thread holds the monitor for that whole
 * window, but "empty outside INITIALIZING" is only true after the commit.
 *
 * <p>Peer traffic goes last because it is the only step with no protocol meaning: the adapter
 * learns the game is gone from the GPGNet socket closing, and datagrams still in flight at that
 * point are dropped by an adapter that has already torn down the game session (verified below).
 *
 * <p>That step logs its own final summary, closes the socket, and joins the receive loop, so the
 * receiver's totals line is written before this returns rather than landing on a daemon thread
 * afterwards. It does not quiesce everything, though: the send and progress tickers are stopped but
 * not joined, so one straggler round already past its stopped-check can still log after this
 * returns — bounded to a single line each, with the bootstrap's log shutdown following.
 *
 * <p><b>Steps one and three are not a whole-system quiesce.</b> {@link StateMachine#cancel()}
 * cancels only the StateMachine's own timer, and {@link MockGameLifecycle#stopSchedules()} calls
 * {@code shutdownNow()} on the launch-delay and match-duration scheduler, which drains the tasks
 * that have not started yet. What it cannot recall is a task already past its cancellation check
 * and running, which can still post an event after teardown. That residue is inert: the
 * invalid-transition policy is IGNORE, so a stray event in LIVE or ENDED is logged and dropped, and
 * a transition that does fire converges on ENDED, whose entry hook is this once-guarded sequence,
 * so nothing tears down twice.
 *
 * <p>Step one is what makes that true, and it is why it leads rather than sitting next to {@code
 * cancel()}. Before WBS-2.3.11 added it, nothing stopped that scheduler: a {@code SIGTERM} in
 * HOSTING or JOINING with a launch still pending left the FSM in that state (the local close is
 * filtered, see {@code MockGameLifecycle.setupStateMachine}), and the orphaned {@code LaunchMatch}
 * then drove the registered transition to LIVE against a socket this sequence had already closed.
 * Draining the queue removes that case.
 *
 * <p>Verified in faf-ice-adapter: {@code GPGNetServer.onGpgnetConnectionLost} closes the client,
 * reports {@code Disconnected} over RPC and calls {@code IceAdapter.onFAShutdown}, which runs
 * {@code GameSession.close} — closing every peer relay and clearing the peer map. Its accept loop
 * then goes back to waiting, so the adapter treats a game disconnect as that game's shutdown and
 * keeps running.
 *
 * <p><b>Logging is deliberately not stopped here</b> (WBS-3.2.5.1). Flushing and stopping the
 * logging context is process-global and one-way: {@code LoggingSetup.shutdown()} detaches the root
 * appenders for the rest of the JVM. This sequence runs from the FSM's ENDED entry hook, so doing
 * it here blanked every later log line in the same JVM — harmless in production, but it silenced
 * the mock-game test suite after the first lifecycle reached ENDED. The bootstrap ({@code Main})
 * now owns that step as the last statement on both of its exit paths, which is the only place that
 * genuinely knows the process is about to end.
 *
 * <p>This is the game-side sibling of the mock client's {@code SessionTeardown} (R33), minus the
 * subprocess management: the game owns exactly one connection and spawns no children, so this is a
 * simple once-guard rather than a registry or multi-resource orchestrator.
 *
 * <p><b>Idempotent and convergent.</b> The first {@link #run()} wins; later calls return
 * immediately. All three callers — the FSM's ENDED phase (self-initiated exit), the JVM shutdown
 * hook installed by the bootstrap (3.2.5.1, for {@code SIGTERM} / {@code Ctrl-C}), and that
 * bootstrap's own post-wait safety call — share one instance, so every path converges here with no
 * double-teardown. Each step is exception-isolated: a failing step is logged and the rest continue.
 *
 * <p><b>The once-guard is lock-free on purpose.</b> A {@code synchronized run()} deadlocks the two
 * callers against each other: the ENDED entry hook runs inside {@link
 * StateMachine#receiveEvent(com.faforever.testharness.shared.statemachine.Event)}, which is
 * synchronized, so the FSM thread takes the StateMachine monitor and then this one, while the JVM
 * hook thread takes this monitor and then wants the StateMachine's inside {@link
 * StateMachine#cancel()}. The compare-and-set guard removes the second lock entirely, so the two
 * orders can no longer cross. The cost is that a losing caller returns while the winner is still
 * mid-teardown rather than waiting for it. On the path where that actually happens — a {@code
 * SIGTERM} landing during the ENDED transition — the loser is the JVM hook, which then stops
 * logging and lets the JVM halt while the winner may still be inside this method. Both consequences
 * are benign: the kernel closes the socket the winner was closing, and what is lost is two teardown
 * INFO lines. The alternative was a deadlock.
 *
 * <p><b>Exit code.</b> This sequence does not call {@link System#exit(int)}; the exit code is the
 * bootstrap's, mapped from {@link MockGameLifecycle#getExitStatus()} once the FSM reaches ENDED. A
 * client-initiated {@code SIGTERM} never reaches that mapping: it runs this hook and then the JVM
 * exits with its signal default ({@code 143}), matching the real adapter — R41 (crash detection)
 * must not classify a teardown-time {@code 143} as a crash.
 *
 * <p>Runs synchronously on the calling thread ({@code implements Runnable} so the bootstrap can use
 * it directly as a shutdown-hook body). It is still not lock-free end to end: the caller that wins
 * the guard reaches {@link StateMachine#cancel()}, which is synchronized, so if the FSM thread is
 * mid-transition this blocks until that transition's action returns. What the ordering above buys
 * is that the wait is <em>bounded</em> rather than open-ended — a stalled write is released by step
 * two, so the worst remaining case is an action that is slow for its own reasons. The longest of
 * those is the 500 ms pre-first-frame wait in the lifecycle's INITIALIZING to IDLE step, which
 * closing the socket does not shorten because that action is sleeping rather than writing, plus the
 * traffic step's 500 ms receive-loop join — about a second, against the client's 5 s SIGTERM to
 * SIGKILL grace, so the bound is known rather than merely assumed. Measured against a real adapter
 * it is about 1 ms: the receive thread wakes the moment its socket closes.
 */
public final class GameShutdown implements Runnable {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameShutdown.class);

    /** The lifecycle FSM whose scheduling is stopped, third, once the socket is closed. */
    private final StateMachine fsm;

    /**
     * The GPGNet connection to close; {@code null} until registered, e.g. a game that never
     * connected. Volatile so a late {@link #registerConnection(GpgNetConnection)} is seen by {@link
     * #run()}.
     */
    private volatile GpgNetConnection connection;

    /**
     * The peer traffic session to close; {@code null} until registered, e.g. a game whose lobby
     * socket never bound. Volatile for the same reason as {@link #connection}.
     */
    private volatile GameTrafficSession traffic;

    /**
     * A reference to the lifecycle of the mock game. This is used by the shutdown sequence to
     * cancel any scheduled transitions that exist outside of the FSM.
     */
    private final MockGameLifecycle lifecycle;

    /** Set by the caller that wins {@link #run()}; the lock-free once-guard. */
    private final AtomicBoolean done = new AtomicBoolean();

    /**
     * Creates a shutdown for a game that has not yet opened its GPGNet connection. Register the
     * connection with {@link #registerConnection(GpgNetConnection)} once it exists.
     *
     * @param fsm the lifecycle FSM; must not be {@code null}
     */
    public GameShutdown(final StateMachine fsm) {
        this(fsm, null, null);
    }

    /**
     * Creates a shutdown for a game whose GPGNet connection already exists.
     *
     * @param fsm the lifecycle FSM; must not be {@code null}
     * @param connection the GPGNet connection to close, or {@code null} if not yet opened
     */
    public GameShutdown(final StateMachine fsm, final GpgNetConnection connection) {
        this(fsm, connection, null);
    }

    /**
     * Creates a shutdown for a game whose GPGNet connection already exists.
     *
     * @param fsm the lifecycle FSM; must not be {@code null}
     * @param connection the GPGNet connection to close, or {@code null} if not yet opened
     * @param lifecycle the lifecycle of the mock game, or {@code null} if it does not exist yet
     */
    public GameShutdown(
            final StateMachine fsm,
            final GpgNetConnection connection,
            final MockGameLifecycle lifecycle) {
        this.fsm = Objects.requireNonNull(fsm, "fsm");
        this.connection = connection;
        this.lifecycle = lifecycle;
    }

    /**
     * Registers the GPGNet connection to close on shutdown, once the game has opened it.
     * Registering after {@link #run()} has already executed is a no-op for teardown (the connection
     * will not be closed by this sequence) and is warned about.
     *
     * @param gpgNetConnection the live connection; must not be {@code null}
     */
    public void registerConnection(final GpgNetConnection gpgNetConnection) {
        this.connection = Objects.requireNonNull(gpgNetConnection, "gpgNetConnection");
        if (done.get()) {
            LOG.warn(
                    "GPGNet connection registered after shutdown already ran; "
                            + "it will not be closed by this sequence");
        }
    }

    /**
     * Registers the peer traffic session to close on shutdown (WBS-4.3.2). As with {@link
     * #registerConnection(GpgNetConnection)}, registering after {@link #run()} has already executed
     * leaves the session open and is warned about.
     *
     * @param trafficSession the session owning the lobby socket; must not be {@code null}
     */
    public void registerTrafficSession(final GameTrafficSession trafficSession) {
        this.traffic = Objects.requireNonNull(trafficSession, "trafficSession");
        if (done.get()) {
            LOG.warn(
                    "peer traffic session registered after shutdown already ran; "
                            + "its socket will not be closed by this sequence");
        }
    }

    /**
     * Runs the shutdown sequence once: stop the lifecycle's own scheduling, close the connection,
     * stop FSM scheduling, then close the peer traffic session (the scheduling, connection and
     * traffic steps each skipped if nothing was registered). Subsequent or concurrent calls return
     * immediately. Each step is exception-isolated.
     *
     * <p>The order matters and is the subject of this class's javadoc: closing the connection
     * before {@link StateMachine#cancel()} is what lets a transition action stalled in a blocking
     * write release the StateMachine monitor that cancelling needs.
     */
    @Override
    public void run() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        LOG.info("shutting down mock game");
        stopScheduling();
        closeConnection();
        stopFSM();
        closeTraffic();
        LOG.info("mock game shutdown complete");
    }

    private void stopFSM() {
        try {
            fsm.cancel();
        } catch (RuntimeException e) {
            LOG.warn("failed to stop FSM scheduling: {}", e.getMessage());
        }
    }

    private void stopScheduling() {
        if (lifecycle == null) {
            return;
        }
        try {
            lifecycle.stopSchedules();
        } catch (RuntimeException e) {
            LOG.warn("failed to stop lifecycle scheduling: {}", e.getMessage());
        }
    }

    private void closeConnection() {
        GpgNetConnection current = connection;
        if (current == null) {
            return; // game never connected — nothing to close
        }
        try {
            current.close();
        } catch (RuntimeException e) {
            LOG.warn("failed to close GPGNet connection: {}", e.getMessage());
        }
    }

    private void closeTraffic() {
        GameTrafficSession current = traffic;
        if (current == null) {
            return; // game never exchanged peer traffic — nothing to close
        }
        try {
            current.close();
        } catch (RuntimeException e) {
            LOG.warn("failed to close the peer traffic session: {}", e.getMessage());
        }
    }
}
