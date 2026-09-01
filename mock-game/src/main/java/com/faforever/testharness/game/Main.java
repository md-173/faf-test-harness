package com.faforever.testharness.game;

import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.config.MockGameCli;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.lifecycle.GameShutdown;
import com.faforever.testharness.game.lifecycle.GameState;
import com.faforever.testharness.game.lifecycle.MockGameLifecycle;
import com.faforever.testharness.game.lifecycle.MockGameLifecycle.ExitStatus;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock game process entry point and bootstrap (WBS-3.2.5.1).
 *
 * <p>Pure glue: it constructs the pieces that already exist in the right order and owns the process
 * exit code. The steps, and why they are in this order:
 *
 * <ol>
 *   <li><b>Parse and validate the argv</b> ({@link MockGameCli#parseOrReport}, WBS-3.2.1.2). A bad
 *       argument returns {@link ExitCodes#USAGE} here, before a socket is opened or a thread
 *       started, so a mis-launch never looks like an adapter problem.
 *   <li><b>Construct the lifecycle</b> (WBS-3.2.4.1). It owns the FSM, registers the inbound
 *       handlers (WBS-3.2.2.2), and only then calls {@code connect()} — so a handler is never
 *       registered late enough to drop the adapter's {@code CreateLobby} reply to our first {@code
 *       GameState Idle}. Connect-with-retry lives in {@link GpgNetConnection} (bounded attempts, no
 *       infinite loop) and the not-established window is the FSM's own 30 s timeout into ENDED,
 *       matching the state diagram; the bootstrap adds no window of its own.
 *   <li><b>Install the JVM shutdown hook</b> (WBS-3.2.5.2) on the very next line, sharing the
 *       lifecycle's one {@link com.faforever.testharness.game.lifecycle.GameShutdown} instance so a
 *       {@code SIGTERM} and a self-initiated exit converge on the same once-guarded teardown.
 *   <li><b>Wait for ENDED</b>, then map {@link MockGameLifecycle#getExitStatus()} onto the process
 *       exit code. Every failure the FSM models — connect failure, adapter loss, a failed
 *       transition — ends in ENDED, so this one wait covers them all.
 * </ol>
 *
 * <p><b>Deviation from the card's step order.</b> The card asks for the hook to be installed before
 * any resource opens. That is not reachable: the hook is the lifecycle's {@code GameShutdown},
 * which needs the {@code StateMachine} at construction, and the {@code StateMachine} is built by
 * {@link MockGameLifecycle} — whose constructor also calls {@code connect()}. So the connect
 * attempt does start a few microseconds before any hook exists. Nothing leaks in that window: a
 * signal there kills the JVM and the OS closes the socket; the only casualty is the final log
 * flush.
 *
 * <p><b>The ENDED wait is not unconditionally hang-proof</b>, by design. The FSM arms one timeout,
 * into ENDED, at construction, and {@code StateMachine} clears pending timeouts on every transition
 * — so IDLE and LOBBY, which sit waiting on the adapter for {@code CreateLobby} and {@code
 * HostGame}, have no timeout of their own. An adapter that accepts the socket and then goes quiet
 * leaves the game waiting, exactly as the real game would; state-diagram.md gives a timeout only
 * out of INITIALIZING and states that teardown of the game is always client-led. The card's no-hang
 * criterion is about the <em>unreachable</em> adapter, which the bounded connect retry settles in
 * about two seconds.
 *
 * <p>Stopping the logging context is the last thing this class does, on both exit paths. It is
 * process-global and one-way, so it belongs to whoever knows the process is ending — not to the
 * shutdown sequence, which also runs from the FSM thread mid-life.
 *
 * <p>The exit codes themselves are documented in {@link ExitCodes}, which is the contract the
 * client's crash detection (WBS-3.1.2.4 / WBS-3.1.2.6) reads. Today that client only separates zero
 * from non-zero, so the finer distinctions this class emits are ahead of their consumer; see {@link
 * ExitCodes} for what is and is not load-bearing yet.
 */
public final class Main {

    static {
        LoggingSetup.configure("MockGame");
    }

    /** Logger for mock-game startup messages. */
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /**
     * How long the simulated match runs before the game reports its result and ends. Nothing
     * constrains this value — the client's post-{@code GameEnded} safety net is armed only once
     * {@code GameEnded} has been observed, so it bounds the exit, not the match. It is a plain
     * judgement call: long enough that a session looks like a session in the logs, short enough
     * that an end-to-end harness run does not cost a minute. Five seconds chosen as this
     * compromise.
     */
    private static final Duration MATCH_DURATION = Duration.ofSeconds(5);

    private Main() {}

    /**
     * Entry point: boots the game and exits with the resulting code.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        int exitCode;
        try {
            exitCode = run(args, MATCH_DURATION);
        } finally {
            // In a finally so an unchecked throw out of run still flushes and stops logging. It
            // does not catch: the JVM's uncaught-exception path still exits 1 either way, and
            // remapping would lose the stack trace. What the finally buys is that the teardown
            // lines explaining the failure reach the log before the context goes away.
            LoggingSetup.shutdown();
        }
        System.exit(exitCode);
    }

    /**
     * Runs one mock game to completion and returns the process exit code it should produce.
     *
     * <p>Split out of {@link #main(String[])} so tests can drive the whole boot path in-JVM,
     * against a scripted GPGNet server and with short durations, instead of exec'ing the binary and
     * scraping its exit status. It deliberately does not call {@link System#exit(int)} or stop
     * logging — both belong to {@code main}.
     *
     * <p>The auto-launch delay is <em>not</em> a parameter: it is a launch argument ({@code
     * --launch-delay-seconds}, WBS-4.3.1), so a test drives it the same way the launcher does. The
     * match duration stays one, because nothing on the wire sets it and a test needs it short.
     *
     * @param args the raw argv as passed to {@code main}
     * @param matchDuration how long the simulated match runs
     * @return the exit code this run should produce; see {@link ExitCodes}
     */
    static int run(final String[] args, final Duration matchDuration) {
        MockGameCli.ParseOutcome outcome = MockGameCli.parseOrReport(args, System.err);
        if (outcome.exitCode() != ExitCodes.OK) {
            return outcome.exitCode();
        }
        MockGameConfig config = outcome.config();
        // The launch policy is logged with the rest of the startup line, and in words rather than
        // as the raw seconds, so a hand-run binary that took the default still says out loud
        // whether it intends to launch on its own — the one case where the default decides
        // anything (see MockGameCli's class javadoc).
        LOG.info(
                "mock game started: playerId={} login={} gameUid={} "
                        + "gpgNetPort={} lobbyPort={} gameOptions={} launch={}",
                config.playerId(),
                config.playerLogin(),
                config.gameUid(),
                config.gpgNetPort(),
                config.lobbyPort(),
                config.gameOptions(),
                config.launchDelay()
                        .map(delay -> "auto after " + delay.toSeconds() + "s")
                        .orElse("manual only (auto-launch disabled)"));

        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        config,
                        new GpgNetConnection(config.gpgNetPort()),
                        config.launchDelay().orElse(null),
                        matchDuration);
        Thread hook =
                new Thread(
                        shutdownHook(lifecycle.shutdown(), LoggingSetup::shutdown),
                        "mock-game-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException e) {
            // A signal landed while the lifecycle was being constructed. Tear down here and
            // return: with no hook registered, the JVM halts once the shutdown sequence finishes,
            // and the wait below would never return for the finally to run.
            LOG.info("shutdown already in progress at boot; tearing down without a hook");
            lifecycle.shutdown().run();
            return ExitCodes.RUNTIME;
        }
        try {
            lifecycle.stateReached(GameState.ENDED).join();
            ExitStatus status = lifecycle.getExitStatus();
            int exitCode = exitCode(status);
            LOG.info("mock game finished: status={}, exit code {}", status, exitCode);
            return exitCode;
        } finally {
            // Belt and braces. Reaching here normally means the FSM's ENDED entry already ran the
            // sequence, so this is a no-op; it earns its place only if the wait above ended by
            // throwing.
            lifecycle.shutdown().run();
            removeHook(hook);
        }
    }

    /**
     * The body of the JVM shutdown hook: tear the game down, then stop logging.
     *
     * <p>Extracted so the {@code SIGTERM} path is testable in-JVM at every phase — pre-connect,
     * connected, and mid-FSM — which a lambda buried in {@link #run} was not. The log-stop step is
     * a parameter for the same reason: a test injects a recording one, because the real {@code
     * LoggingSetup.shutdown()} would detach the root appenders for the rest of the suite. That is
     * the same hazard that got this step moved out of {@code GameShutdown} in the first place.
     *
     * <p>Order matters: teardown logs, so stopping logging first would swallow its output.
     *
     * <p>Takes the sequence rather than the lifecycle that owns it, since that is all it needs. A
     * test can then hand it one built over a connection it controls, instead of registering a
     * stand-in onto a live lifecycle's sequence.
     *
     * @param shutdown the game's once-guarded shutdown sequence, shared with the FSM's ENDED phase
     * @param logFlush the flush-and-stop-logging step; {@code LoggingSetup::shutdown} in production
     * @return the hook body
     */
    static Runnable shutdownHook(final GameShutdown shutdown, final Runnable logFlush) {
        return () -> {
            shutdown.run();
            logFlush.run();
        };
    }

    /**
     * Maps the lifecycle's outcome onto the documented process exit code.
     *
     * @param status the lifecycle's exit status, read once it has reached ENDED
     * @return the matching code from {@link ExitCodes}
     */
    private static int exitCode(final ExitStatus status) {
        return switch (status) {
            case OK -> ExitCodes.OK;
            case SERVER_CONNECTION_LOST -> ExitCodes.ADAPTER_LOST;
            // A game that never reached the adapter and a generic failure are both "this run did
            // not work"; the log line above carries which one it was.
            case SERVER_NOT_CONNECTED, FAILED -> ExitCodes.RUNTIME;
        };
    }

    /**
     * Unregisters the shutdown hook once the run has finished under its own power, so a repeated
     * in-JVM {@link #run} does not accumulate hooks.
     *
     * @param hook the hook installed by {@link #run}
     */
    private static void removeHook(final Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // Shutdown is already under way — a SIGTERM landed as the run was finishing. The hook
            // is running or has run, and there is nothing left to unregister.
            LOG.debug("shutdown already in progress; leaving the hook in place");
        }
    }
}
