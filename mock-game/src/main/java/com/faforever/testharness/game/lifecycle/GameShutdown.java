package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.net.GameTrafficSession;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mock game's single, idempotent shutdown sequence (WBS-3.2.5.2). Runs three steps, in order:
 *
 * <ol>
 *   <li>stop the lifecycle FSM's time-based scheduling ({@link StateMachine#cancel()}), so no
 *       timeout queued <em>on the FSM itself</em> fires a transition mid-teardown;
 *   <li>close the {@link GpgNetConnection} — closing the socket <em>is</em> the shutdown protocol;
 *       no farewell frame is sent;
 *   <li>close the {@link GameTrafficSession} (WBS-4.3.2), which stops the peer traffic cadence and
 *       closes the shared lobby socket, ending the receiver's loop.
 * </ol>
 *
 * <p>Peer traffic goes last because it is the only step with no protocol meaning: the adapter
 * learns the game is gone from the GPGNet socket closing, and datagrams still in flight at that
 * point are dropped by an adapter that has already torn down the game session. That step is
 * synchronous end to end — it logs its own final summary, closes the socket, and waits briefly for
 * the receive loop to finish logging — so teardown leaves no thread still writing once this
 * returns, and the bootstrap can stop the logging context immediately afterwards.
 *
 * <p><b>Step one is not a whole-system quiesce.</b> {@link StateMachine#cancel()} cancels only the
 * StateMachine's own timer. {@link MockGameLifecycle}'s launch-delay and match-duration tasks run
 * on a separate scheduler this sequence does not stop, so one can still post an event after
 * teardown. Usually inert — the invalid-transition policy is IGNORE, so a stray event in LIVE or
 * ENDED is logged and dropped — but not always: a {@code SIGTERM} in HOSTING or JOINING with a
 * launch still pending leaves the FSM in that state (the local close is filtered, see {@code
 * MockGameLifecycle.setupStateMachine}), and the orphaned {@code LaunchMatch} then drives the
 * registered transition to LIVE against a socket this sequence has already closed. It fails into
 * ENDED, whose entry hook is this once-guarded sequence, so nothing tears down twice; the JVM is
 * halting regardless. Tracked against WBS-3.2.4.1, which owns those tasks.
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
 * it directly as a shutdown-hook body). It is not lock-free end to end: the caller that wins the
 * guard still calls {@link StateMachine#cancel()}, which is synchronized, so if the FSM thread is
 * mid-transition this blocks until that transition's action returns. The longest such action is the
 * 500 ms pre-first-frame wait in the lifecycle's INITIALIZING to IDLE step, against the client's 5
 * s SIGTERM to SIGKILL grace, so the bound is known rather than merely assumed.
 */
public final class GameShutdown implements Runnable {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameShutdown.class);

    /** The lifecycle FSM whose scheduling is stopped first. */
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

    /** Set by the caller that wins {@link #run()}; the lock-free once-guard. */
    private final AtomicBoolean done = new AtomicBoolean();

    /**
     * Creates a shutdown for a game that has not yet opened its GPGNet connection. Register the
     * connection with {@link #registerConnection(GpgNetConnection)} once it exists.
     *
     * @param fsm the lifecycle FSM; must not be {@code null}
     */
    public GameShutdown(final StateMachine fsm) {
        this(fsm, null);
    }

    /**
     * Creates a shutdown for a game whose GPGNet connection already exists.
     *
     * @param fsm the lifecycle FSM; must not be {@code null}
     * @param connection the GPGNet connection to close, or {@code null} if not yet opened
     */
    public GameShutdown(final StateMachine fsm, final GpgNetConnection connection) {
        this.fsm = Objects.requireNonNull(fsm, "fsm");
        this.connection = connection;
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
     * Runs the shutdown sequence once: stop FSM scheduling, close the connection, then close the
     * peer traffic session (either of the last two skipped if none was registered). Subsequent or
     * concurrent calls return immediately. Each step is exception-isolated.
     */
    @Override
    public void run() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        LOG.info("shutting down mock game");
        stopScheduling();
        closeConnection();
        closeTraffic();
        LOG.info("mock game shutdown complete");
    }

    private void stopScheduling() {
        try {
            fsm.cancel();
        } catch (RuntimeException e) {
            LOG.warn("failed to stop FSM scheduling: {}", e.getMessage());
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
