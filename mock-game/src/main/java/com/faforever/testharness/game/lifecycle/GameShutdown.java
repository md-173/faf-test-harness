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
 *   <li>stop the lifecycle FSM's time-based scheduling ({@link StateMachine#cancel()}), so no
 *       timeout queued <em>on the FSM itself</em> starts a transition mid-teardown;
 *   <li>close the {@link GpgNetConnection}: closing the socket <em>is</em> the shutdown protocol,
 *       and no farewell frame is sent;
 *   <li>close the {@link GameTrafficSession} (WBS-4.3.2), which stops the peer traffic cadence and
 *       closes the shared lobby socket, ending the receiver's loop.
 * </ol>
 *
 * <p>{@link MockGameLifecycle} uses two separate schedulers: the internal {@link StateMachine} one
 * for timeouts and another for launch-delay and match-duration tasks. This is why there are two
 * very similar steps. Step 2 cancels the StateMachine's own timer while step 1 cancels the other
 * MockGameLifecycle scheduler, so both have stopped before anything is closed, and neither waits
 * for the StateMachine monitor. Nor does either interrupt the thread running this sequence, which
 * is often one of those schedulers' own: a timeout, or a match that ends on its own, drives the
 * game into ENDED, whose entry hook runs this sequence, and an interrupt would cut the traffic
 * step's wait for its receiver short (WBS-2.3.7-fix, #465; WBS-3.2.4.1-fix).
 *
 * <p><b>Stopping scheduling first is safe because {@link StateMachine#cancel()} never waits for the
 * StateMachine monitor</b> (WBS-2.3.7-fix, #328). Every outbound frame is written from a transition
 * action inside the synchronized {@link
 * StateMachine#receiveEvent(com.faforever.testharness.shared.statemachine.Event)}, and {@link
 * GpgNetConnection#send} is a blocking write. If the adapter stops reading, the kernel send buffer
 * fills and the FSM thread blocks in that write still holding the StateMachine monitor. That is not
 * hypothetical: in the pinned faf-ice-adapter, {@code GPGNetServer$GPGNetClient.listenerThread}
 * calls {@code processGpgnetMessage} inline in its read loop, which reaches {@code
 * RPCService.onGpgNetMessageReceived} and then {@code getPeerOrWait}, an untimed {@code
 * CompletableFuture.get()} on the first JSON-RPC peer. Until a peer attaches, the adapter accepts
 * this game's connection and then stops reading it with the socket still open. Step three's close
 * turns the stalled write into an immediate {@code IOException}, the action fails into ENDED, and
 * the monitor is released, but nothing here waits for that to happen. While {@code cancel()} was
 * synchronized it did wait, behind the one write only the later close could break, so #299 moved
 * the close ahead of it and let an FSM timeout fire between the two; #328 took {@code cancel()} off
 * that monitor and put the order back.
 *
 * <p><b>The close step stays off the monitor too, because a local close is not news to the FSM.</b>
 * That matters only at the one place {@link GpgNetConnection} can deliver a disconnect on the
 * calling thread. Every other site delivers it on the connection's reader thread (the read loop,
 * and each branch where the connect gives up), which cannot hold up teardown whatever it does, and
 * each of those reads the close flag, so one that reads it after this step has requested the close
 * reports {@code LOCAL_CLOSE} too. But on a connection that never opened its socket, {@code
 * close()} can fire the listener <em>synchronously on the calling thread</em>. {@code
 * MockGameLifecycle.setupStateMachine} filters {@code LOCAL_CLOSE} at the source rather than
 * posting it, so that synchronous call returns without touching the FSM. Were it ever to post an
 * event instead, this step would take the StateMachine monitor and wait behind whatever transition
 * held it. With no socket open there is no write to stall behind, so the wait would be short, but
 * it would break the property everything above relies on: that no step in this sequence waits for
 * that monitor. That filter is therefore part of this sequence's design and not merely a log-noise
 * fix, which is how it is described at its own call site.
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
 * <p><b>Steps one and two are not a whole-system quiesce.</b> {@link StateMachine#cancel()} cancels
 * only the StateMachine's own timer, and {@link MockGameLifecycle#stopSchedules()} shuts down the
 * launch-delay and match-duration scheduler, which discards the tasks that have not started yet.
 * Neither can recall work already running: a scheduler task past its cancellation check can still
 * post an event after teardown, and a timeout whose transition is already under way when {@code
 * cancel()} runs still completes; both FSM timeouts, the GPGNet connect timeout and the optional
 * lobby give-up timer, target ENDED. That residue is inert: the invalid-transition policy is
 * IGNORE, so a stray event in LIVE or ENDED is logged and dropped, and a transition that does fire
 * converges on ENDED, whose entry hook is this once-guarded sequence, so nothing tears down twice.
 *
 * <p>Step one is what makes that true. Before WBS-3.2.4.1-fix (#265) added it, nothing stopped that
 * scheduler: a {@code SIGTERM} in HOSTING or JOINING with a launch still pending left the FSM in
 * that state (the local close is filtered, see {@code MockGameLifecycle.setupStateMachine}), and
 * the orphaned {@code LaunchMatch} then drove the registered transition to LIVE against a socket
 * this sequence had already closed. Draining the queue removes that case.
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
 * <p><b>The once-guard is lock-free on purpose.</b> A {@code synchronized run()} deadlocked the two
 * callers against each other while {@link StateMachine#cancel()} was synchronized too: the ENDED
 * entry hook runs inside {@link
 * StateMachine#receiveEvent(com.faforever.testharness.shared.statemachine.Event)}, which is
 * synchronized, so the FSM thread took the StateMachine monitor and then this one, while the JVM
 * hook thread took this monitor and then wanted the StateMachine's inside {@code cancel()}. That
 * crossing went with #328, and the compare-and-set guard keeps a second lock out of teardown so it
 * cannot come back. The cost is that a losing caller returns while the winner is still mid-teardown
 * rather than waiting for it. On the path where that actually happens, a {@code SIGTERM} landing
 * during the ENDED transition, either caller can lose. The JVM hook usually wins while the
 * transition's action is still sending, and the FSM thread's entry hook then returns at once and
 * commits ENDED while the hook is still tearing down. If the entry hook wins instead, the JVM hook
 * returns at once, stops logging and lets the JVM halt while the winner may still be inside this
 * method. Both are benign: the kernel closes the socket the winner was closing, and what the guard
 * costs is at most a few teardown INFO lines. The signal itself can cost more: any closing frame
 * the ENDED action has not sent when the JVM hook closes the socket is lost, as for a real game
 * killed mid-send.
 *
 * <p><b>Exit code.</b> This sequence does not call {@link System#exit(int)}; the exit code is the
 * bootstrap's, mapped from {@link MockGameLifecycle#getExitStatus()} once the FSM reaches ENDED. A
 * client-initiated {@code SIGTERM} never reaches that mapping: it runs this hook and then the JVM
 * exits with its signal default ({@code 143}), matching the real adapter — R41 (crash detection)
 * must not classify a teardown-time {@code 143} as a crash.
 *
 * <p>Runs synchronously on the calling thread ({@code implements Runnable} so the bootstrap can use
 * it directly as a shutdown-hook body), and no step waits for the StateMachine monitor, so a
 * transition in flight, however slow, does not hold teardown up. The one bounded wait left is the
 * traffic step's 500 ms receive-loop join, against the client's 5 s SIGTERM to SIGKILL grace;
 * measured against a real adapter it takes about 1 ms, because the receive thread wakes the moment
 * its socket closes. The flip side is that the JVM hook can now return while such a transition is
 * still running, so that transition's last log lines can be lost when the bootstrap stops logging
 * and the JVM halts. Until #328, {@code cancel()} waited for it, for up to the 500 ms
 * pre-first-frame wait in the lifecycle's INITIALIZING to IDLE step.
 */
public final class GameShutdown implements Runnable {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameShutdown.class);

    /** The lifecycle FSM whose scheduling is stopped second, before the socket is closed. */
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
     * Runs the shutdown sequence once: stop the lifecycle's own scheduling, stop FSM scheduling,
     * close the connection, then close the peer traffic session (the scheduling, connection and
     * traffic steps each skipped if nothing was registered). Subsequent or concurrent calls return
     * immediately. Each step is exception-isolated.
     *
     * <p>The order is the subject of this class's javadoc: scheduling stops first so that no queued
     * timeout starts a transition mid-teardown, which is safe because {@link StateMachine#cancel()}
     * never waits for the StateMachine monitor a stalled transition action may be holding.
     */
    @Override
    public void run() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        LOG.info("shutting down mock game");
        stopScheduling();
        stopFSM();
        closeConnection();
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
