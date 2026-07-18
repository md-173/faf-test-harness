package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mock game's single, idempotent shutdown sequence (WBS-3.2.5.2). Runs three steps, in order:
 *
 * <ol>
 *   <li>stop the lifecycle FSM's time-based scheduling ({@link StateMachine#cancel()}), so no
 *       queued timeout fires a transition mid-teardown;
 *   <li>close the {@link GpgNetConnection} — closing the socket <em>is</em> the shutdown protocol
 *       (the adapter treats a game disconnect as shutdown, drops this client's peers, tears down
 *       its ICE state, and keeps running); no farewell frame is sent;
 *   <li>flush and stop the logging context ({@link LoggingSetup#shutdown()}) — last, so the earlier
 *       steps are still logged and buffered records reach the JSONL file before the JVM exits.
 * </ol>
 *
 * <p>This is the game-side sibling of the mock client's {@code SessionTeardown} (R33), minus the
 * subprocess management: the game owns exactly one connection and spawns no children, so this is a
 * simple once-guard rather than a registry or multi-resource orchestrator.
 *
 * <p><b>Idempotent and convergent.</b> The first {@link #run()} wins; later calls (including a
 * concurrent one, which blocks until the first finishes) are no-ops. The two callers — the FSM's
 * ENDED phase (self-initiated exit) and the JVM shutdown hook installed by the bootstrap (3.2.5.1,
 * for {@code SIGTERM} / {@code Ctrl-C}) — share one instance, so both paths converge here with no
 * double-teardown. Each step is exception-isolated: a failing step is logged and the rest continue.
 *
 * <p><b>Exit code.</b> This sequence does not call {@link System#exit(int)}; the exit code follows
 * the invocation path. A self-initiated exit lets {@code main} return normally → {@code 0}; a
 * client-initiated {@code SIGTERM} runs this hook and then the JVM exits with its signal default
 * ({@code 143}), matching the real adapter — R41 (crash detection) must not classify a
 * teardown-time {@code 143} as a crash.
 *
 * <p>Runs synchronously on the calling thread ({@code implements Runnable} so the bootstrap can use
 * it directly as a shutdown-hook body), and completes well within the client's SIGTERM→SIGKILL
 * grace: the connection close is a synchronous socket close and the FSM/logging stops are local.
 */
public final class GameShutdown implements Runnable {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameShutdown.class);

    /** The lifecycle FSM whose scheduling is stopped first. */
    private final StateMachine fsm;

    /** Flushes and stops the logging context; the JVM-global {@link LoggingSetup#shutdown()}. */
    private final Runnable logFlush;

    /**
     * The GPGNet connection to close; {@code null} until registered, e.g. a game that never
     * connected. Volatile so a late {@link #registerConnection(GpgNetConnection)} is seen by {@link
     * #run()}.
     */
    private volatile GpgNetConnection connection;

    /** True once {@link #run()} has executed; volatile for the lock-free read in registration. */
    private volatile boolean done;

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
        this(fsm, connection, LoggingSetup::shutdown);
    }

    /**
     * Full-control constructor — used by tests to inject a recording log-flush so the real logging
     * context is not stopped mid-suite.
     *
     * @param fsm the lifecycle FSM; must not be {@code null}
     * @param connection the GPGNet connection to close, or {@code null} if not yet opened
     * @param logFlush the flush/stop-logging step; must not be {@code null}
     */
    GameShutdown(
            final StateMachine fsm, final GpgNetConnection connection, final Runnable logFlush) {
        this.fsm = Objects.requireNonNull(fsm, "fsm");
        this.connection = connection;
        this.logFlush = Objects.requireNonNull(logFlush, "logFlush");
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
        if (done) {
            LOG.warn(
                    "GPGNet connection registered after shutdown already ran; "
                            + "it will not be closed by this sequence");
        }
    }

    /**
     * Runs the shutdown sequence once: stop FSM scheduling, close the connection (skipped if none
     * was registered), then flush and stop logging. Subsequent or concurrent calls are no-ops. Each
     * step is exception-isolated.
     */
    @Override
    public synchronized void run() {
        if (done) {
            return;
        }
        done = true;
        LOG.info("shutting down mock game");
        stopScheduling();
        closeConnection();
        LOG.info("mock game shutdown complete");
        flushLogging();
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

    private void flushLogging() {
        try {
            logFlush.run();
        } catch (RuntimeException e) {
            // Logging is being torn down anyway; there is nowhere useful left to report this.
            LOG.warn("failed to flush/stop logging: {}", e.getMessage());
        }
    }
}
