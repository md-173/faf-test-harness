package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mock game's single, idempotent shutdown sequence (WBS-3.2.5.2). Runs two steps, in order:
 *
 * <ol>
 *   <li>close the {@link GpgNetConnection} — closing the socket <em>is</em> the shutdown protocol;
 *       no farewell frame is sent;
 *   <li>stop the lifecycle FSM's time-based scheduling ({@link StateMachine#cancel()}).
 * </ol>
 *
 * <p><b>The close leads because it is the step that can unblock the other one.</b> Every outbound
 * frame is written from a transition action inside the synchronized {@link
 * StateMachine#receiveEvent(com.faforever.testharness.shared.statemachine.Event)}, and {@link
 * GpgNetConnection#send} is a blocking write. If the adapter stops reading, the kernel send buffer
 * fills and the FSM thread blocks in that write still holding the StateMachine monitor — so {@link
 * StateMachine#cancel()}, which needs that monitor, would wait behind it forever while the one
 * action that would break the stall, closing the socket, sat behind the wait. That is not
 * hypothetical: in the pinned faf-ice-adapter, {@code GPGNetServer$GPGNetClient.listenerThread}
 * calls {@code processGpgnetMessage} inline in its read loop, which reaches {@code
 * RPCService.onGpgNetMessageReceived} and then {@code getPeerOrWait}, an untimed {@code
 * CompletableFuture.get()} on the first JSON-RPC peer. Until a peer attaches, the adapter accepts
 * this game's connection and then stops reading it with the socket still open. Closing first turns
 * the stalled write into an immediate {@code IOException}, the action fails into ENDED, and the
 * monitor is released. Reversing these two steps reinstates the hang (WBS-3.2.5.2 / #299).
 *
 * <p><b>Closing first is only safe because a local close is not news to the FSM.</b> The risk is
 * confined to one of {@link GpgNetConnection#close()}'s two dispatch paths. With a live socket the
 * disconnect is delivered on the reader thread, which cannot hold up teardown whatever it does; but
 * on a connection that never opened its socket, {@code close()} fires the listener
 * <em>synchronously on the calling thread</em>. {@code MockGameLifecycle.setupStateMachine} filters
 * {@code LOCAL_CLOSE} at the source rather than posting it, so that synchronous call returns
 * without touching the FSM. Were it ever to post an event instead, this step would take the
 * StateMachine monitor and block behind the very stall it exists to break — the same defect, moved
 * one line down. That filter is therefore a precondition of this ordering and not merely a
 * log-noise fix, which is how it is currently described at its own call site.
 *
 * <p><b>The cost: a queued transition can now fire between the two steps.</b> The old order stopped
 * scheduling first precisely so nothing could. Scoping what is actually left in that window: {@link
 * MockGameLifecycle} calls {@link StateMachine#setTimeout(long,
 * com.faforever.testharness.shared.statemachine.State,
 * com.faforever.testharness.shared.statemachine.TransitionAction)} exactly once, for the GPGNet
 * connect timeout, and every committed transition cancels and clears all pending timeouts — so the
 * FSM's own timer can only fire here while the machine is still in INITIALIZING. That timeout
 * writes nothing; it assigns SERVER_NOT_CONNECTED and targets ENDED, whose entry hook is this
 * once-guarded sequence, so it converges where teardown was already going and the re-entrant {@link
 * #run()} returns on the guard. This is a property of {@link MockGameLifecycle}'s call sites rather
 * than of {@link StateMachine}, so it has to be re-checked if a second {@code setTimeout} is ever
 * added.
 *
 * <p>Two refinements to that scoping. First, the FSM's timer is not the only thing that can fire in
 * the gap, and not the likeliest: {@link MockGameLifecycle}'s launch-delay and match-duration tasks
 * run on a separate scheduler this sequence does not stop (WBS-3.2.4.1), so a {@code SIGTERM} in
 * HOSTING, JOINING or LIVE can still post {@code LaunchMatch} or {@code GameEnded} afterwards.
 * Under this order they resolve sooner rather than later — the socket is already closed, so the
 * send fails at once, and the transition fails into ENDED where the once-guard makes it a no-op.
 * Second, the timeout list is not yet cleared at the instant this runs from ENDED's entry hook:
 * {@code Transition.transition} fires {@code to.entry()} before {@code receiveEvent} commits.
 * Harmless, because the FSM thread holds the monitor for that whole window, but "empty outside
 * INITIALIZING" is only true after the commit.
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
 * is that the wait is now <em>bounded</em> rather than open-ended — a stalled write is released by
 * step one, so the worst remaining case is an action that is slow for its own reasons. The longest
 * of those is the 500 ms pre-first-frame wait in the lifecycle's INITIALIZING to IDLE step, which
 * closing the socket does not shorten because that action is sleeping rather than writing. Against
 * the client's 5 s SIGTERM to SIGKILL grace the bound is known rather than merely assumed, and it
 * is the same 500 ms the previous order paid.
 */
public final class GameShutdown implements Runnable {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameShutdown.class);

    /** The lifecycle FSM whose scheduling is stopped, second, once the socket is closed. */
    private final StateMachine fsm;

    /**
     * The GPGNet connection to close; {@code null} until registered, e.g. a game that never
     * connected. Volatile so a late {@link #registerConnection(GpgNetConnection)} is seen by {@link
     * #run()}.
     */
    private volatile GpgNetConnection connection;

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
     * Runs the shutdown sequence once: close the connection (skipped if none was registered), then
     * stop FSM scheduling. Subsequent or concurrent calls return immediately. Each step is
     * exception-isolated.
     *
     * <p>The order matters and is the subject of this class's javadoc: closing first is what lets a
     * transition action stalled in a blocking write release the StateMachine monitor that stopping
     * the scheduling needs.
     */
    @Override
    public void run() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        LOG.info("shutting down mock game");
        closeConnection();
        stopScheduling();
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
}
