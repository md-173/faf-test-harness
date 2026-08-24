package com.faforever.testharness.game;

import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.config.MockGameCli;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
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
 *       exit code. Every failure path — connect failure, adapter loss, a failed transition — ends
 *       in ENDED, so this single wait covers them all and cannot hang: the FSM always has a timeout
 *       or a disconnect that drives it there.
 * </ol>
 *
 * <p>Stopping the logging context is the last thing this class does, on both exit paths. It is
 * process-global and one-way, so it belongs to whoever knows the process is ending — not to the
 * shutdown sequence, which also runs from the FSM thread mid-life.
 *
 * <p>The exit codes themselves are documented in {@link ExitCodes}; the client's crash detection
 * (WBS-3.1.2.4 / WBS-3.1.2.6) reads them.
 */
public final class Main {

    static {
        LoggingSetup.configure("MockGame");
    }

    /** Logger for mock-game startup messages. */
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /**
     * How long the game sits in the lobby before starting the match. There is no flag for it and
     * none is planned: the real game launches when a human clicks, which a mock has to stand in for
     * with a timer. Long enough that a peer's {@code ConnectToPeer} lands first, short enough not
     * to pad every harness run.
     */
    private static final Duration LAUNCH_DELAY = Duration.ofSeconds(5);

    /**
     * How long the simulated match runs before the game reports its result and ends. Sized so a
     * full session completes well inside the client's 30 s post-{@code GameEnded} safety net
     * without making an end-to-end run cost a minute.
     */
    private static final Duration MATCH_DURATION = Duration.ofSeconds(30);

    private Main() {}

    /**
     * Entry point: boots the game and exits with the resulting code.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        int exitCode = run(args, LAUNCH_DELAY, MATCH_DURATION);
        LoggingSetup.shutdown();
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
     * @param args the raw argv as passed to {@code main}
     * @param launchDelay how long to sit in the lobby before starting the match
     * @param matchDuration how long the simulated match runs
     * @return the exit code this run should produce; see {@link ExitCodes}
     */
    static int run(final String[] args, final Duration launchDelay, final Duration matchDuration) {
        MockGameCli.ParseOutcome outcome = MockGameCli.parseOrReport(args, System.err);
        if (outcome.exitCode() != ExitCodes.OK) {
            return outcome.exitCode();
        }
        MockGameConfig config = outcome.config();
        LOG.info(
                "mock game started: playerId={} login={} gameUid={} "
                        + "gpgNetPort={} lobbyPort={} gameOptions={}",
                config.playerId(),
                config.playerLogin(),
                config.gameUid(),
                config.gpgNetPort(),
                config.lobbyPort(),
                config.gameOptions());

        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        config,
                        new GpgNetConnection(config.gpgNetPort()),
                        launchDelay,
                        matchDuration);
        Thread hook =
                new Thread(
                        () -> {
                            lifecycle.shutdown().run();
                            LoggingSetup.shutdown();
                        },
                        "mock-game-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            lifecycle.stateReached(GameState.ENDED).join();
            ExitStatus status = lifecycle.getExitStatus();
            int exitCode = exitCode(status);
            LOG.info("mock game finished: status={}, exit code {}", status, exitCode);
            return exitCode;
        } finally {
            // A no-op if the FSM's ENDED entry already ran it; the one case it does work is a
            // failure that left the FSM short of ENDED.
            lifecycle.shutdown().run();
            removeHook(hook);
        }
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
