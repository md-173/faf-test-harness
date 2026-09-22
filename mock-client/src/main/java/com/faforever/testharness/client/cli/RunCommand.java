package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.config.VersionProvider;
import com.faforever.testharness.client.lobby.AuthenticationException;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.TokenSources;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.client.state.ClientState;
import com.faforever.testharness.client.state.MockClientLifecycle;
import com.faforever.testharness.shared.logging.LoggingSetup;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * {@code run} subcommand: connect to the lobby, authenticate, hydrate the welcome state, and sit
 * idle (WBS-3.1.1.4) — with the lifecycle FSM (WBS-3.1.3.1) tracking every phase. This is the
 * user-facing umbrella over the three lobby pillars — transport (3.1.1.1), auth (3.1.1.2), and
 * welcome (3.1.1.3) — wired together via {@link LobbySession} and driven by {@link
 * MockClientLifecycle}.
 *
 * <p>The flow is {@code connect → ask_session → session → auth → welcome} (CONNECTING → IDLE on the
 * FSM), after which the client logs its player id and blocks until the FSM reaches TERMINATED — a
 * lobby disconnect, or a full game session once the lobby drives one (game_launch → STARTING_GAME →
 * … , WBS-3.1.3.3). The idle heartbeat is handled by the transport, which auto-replies {@code pong}
 * to lobby {@code ping}s.
 *
 * <p>A JVM shutdown hook runs the coordinated {@link SessionTeardown} on {@code Ctrl-C} / {@code
 * SIGTERM} (WBS-3.1.3.2) — subprocesses first, then connections. The lobby connection is registered
 * from startup and the lifecycle registers the game process at launch (WBS-3.1.2.4); the adapter
 * handles join via their own wiring (R38, R59b). The resulting disconnect event drives the FSM to
 * TERMINATED. The process exit code then follows the signal per the JVM default (130 for SIGINT,
 * 143 for SIGTERM) — the close itself is clean, with no error logs.
 *
 * <p>Out of scope (later sprints): matchmaking/host/join initiation, and reconnect/recovery.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        exitCodeOnExecutionException = ExitCodes.RUNTIME,
        description = "Connect to the lobby, authenticate, and sit idle until interrupted.")
public final class RunCommand implements Callable<Integer> {

    /** Bound on the whole session setup: WebSocket open plus {@code ask_session → welcome}. */
    private static final Duration SETUP_TIMEOUT = Duration.ofSeconds(45);

    /** Bound on the FSM observing the welcome that already completed the setup future. */
    private static final Duration FSM_SYNC_TIMEOUT = Duration.ofSeconds(5);

    /** Picocli auto-injects the root command so the subcommand can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Validate config, open the lobby session through the lifecycle FSM, and block until the FSM
     * terminates — on a lobby disconnect or a shutdown signal.
     *
     * <p><b>The returned code reaches the process only when this method is what ended the run.</b>
     * On the {@code Ctrl-C} / {@code SIGTERM} path it is computed and then discarded: the shutdown
     * hook installed below is already running by the time this returns, so {@code Main}'s {@link
     * System#exit(int)} never completes and the JVM exits with the signal's own code, 130 or 143
     * (WBS-3.1.3.2-fix, #296). That is the documented outcome for {@code run}; see {@code Main}'s
     * class javadoc for why it is accepted rather than worked around.
     *
     * @return {@link ExitCodes#OK} after a clean close; {@link ExitCodes#RUNTIME} if the session
     *     could not be established, its ICE adapter or game never came up, or the connection
     *     dropped unexpectedly; {@link ExitCodes#ADAPTER_LOST} if the session ran but its ICE
     *     adapter died unaccounted for; {@link ExitCodes#GAME_CRASHED} if the session ran but its
     *     game process died unaccounted for. A dropped connection, a launch that never came up, a
     *     lost adapter and a crashed game are ordered by {@link #sessionExitCode(boolean, boolean,
     *     boolean, boolean, boolean, Logger)}. Superseded by the signal's own exit code whenever a
     *     signal is what ended the run, and then no verdict is logged.
     */
    @Override
    public Integer call() {
        MockClientConfig config = parent.toValidatedConfig(spec);
        MockClientCli.applyLoggingProperties(config);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);
        Logger log = LoggerFactory.getLogger(RunCommand.class);

        TokenSource tokens;
        try {
            tokens = TokenSources.fromConfig(config);
        } catch (AuthenticationException e) {
            log.error("cannot start lobby session: {}", e.getMessage());
            return ExitCodes.RUNTIME;
        }

        LobbyConnection connection = new LobbyConnection(config.lobbyWebSocketUrl());
        LobbySession session =
                new LobbySession(
                        connection,
                        config.uniqueId(),
                        config.clientVersion(),
                        config.userAgent(),
                        config.uidBinaryPath());
        SessionTeardown teardown = new SessionTeardown(connection);
        MockClientLifecycle lifecycle = new MockClientLifecycle(config, session, teardown);

        // Graceful shutdown on Ctrl-C / SIGTERM: run the coordinated teardown synchronously before
        // the JVM exits. The lobby close's disconnect event drives the FSM to TERMINATED, releasing
        // the main thread. No-op if the session has already disconnected (e.g. a server-initiated
        // close that let call() return normally), so a normal exit doesn't emit a spurious
        // "shutdown signal" line. The flag is set first so the end of this method can tell that a
        // signal, not the session, is what ended the run.
        AtomicBoolean shuttingDown = new AtomicBoolean();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    shuttingDown.set(true);
                                    teardownOnShutdown(session, teardown, log);
                                },
                                "mc-shutdown"));

        SessionState me;
        try {
            me = lifecycle.start(tokens).get(SETUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            lifecycle
                    .stateReached(ClientState.IDLE)
                    .get(FSM_SYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("lobby session timed out before welcome");
            teardown.run();
            return ExitCodes.RUNTIME;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("lobby session failed: {}", cause.getMessage());
            teardown.run();
            return ExitCodes.RUNTIME;
        } catch (InterruptedException e) {
            // Tear down before restoring the interrupt flag: with the flag set, every await inside
            // the teardown would throw immediately, degrading SIGTERM→grace→SIGKILL into an
            // instant kill.
            teardown.run();
            Thread.currentThread().interrupt();
            return ExitCodes.RUNTIME;
        }

        log.info(
                "mock client idle as player id={} login={}; press Ctrl-C to exit",
                me.id(),
                me.login());

        // Log when a match starts
        lifecycle.stateReached(ClientState.PLAYING).thenRun(() -> log.info("Match started"));

        // Idle: the transport auto-pongs lobby pings, so we just wait for the FSM to terminate —
        // via a disconnect (server close, network drop, or the shutdown hook's local close), or a
        // completed game session once the lobby drives one.
        try {
            lifecycle.stateReached(ClientState.TERMINATED).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // stateReached futures complete normally; nothing actionable on this teardown path.
        }

        // The verdicts are read from the lifecycle rather than re-derived from an exit code,
        // because each judgement needs signals the classifier already weighed: the clean-end and
        // teardown flags for the game, and whether teardown was running for the launch and the
        // adapter. Testing teardown here would be useless anyway, since it has always run by this
        // point (the TERMINATED entry hook performs it before the stateReached future above
        // completes).
        LobbyConnection.DisconnectEvent event = session.disconnectEvent().orElse(null);
        boolean lobbyDropped =
                event != null && event.reason() == LobbyConnection.DisconnectReason.ABRUPT_CLOSE;
        return sessionExitCode(
                shuttingDown.get(),
                lobbyDropped,
                lifecycle.launchFailed(),
                lifecycle.adapterLost(),
                lifecycle.gameCrashed(),
                log);
    }

    /**
     * Picks a finished session's exit code from the four verdicts it can carry, and logs the one
     * being reported.
     *
     * <p>The order is deliberate. The lobby drop comes first: a connection that died under the
     * session is a different and more fundamental finding than anything that happened inside one,
     * and it was here first. A launch that never came up is next (#437). It shares {@code RUNTIME}
     * with the lobby drop, so between those two only the logged line differs, and in practice it
     * never meets the two below it: no session ran for an adapter or a game to die in. The adapter
     * comes before the game because it is the verdict with an ordering against this read (#406
     * writes it in the transition action that drives TERMINATED, while {@code gameCrashed} is
     * written on a continuation that may not have run yet), so consulting the game first would let
     * a race pick the code for a run whose adapter died. It also matches cause and effect: an
     * adapter dying is what makes the game react, and never the reverse, since java-ice-adapter
     * closes the game's connection and keeps serving when the game dies.
     *
     * <p>A run a signal ended names no verdict at all. Its code is the signal's own (#334), so the
     * one computed here is discarded and a line would only mislead. The lifecycle's own teardown
     * check cannot promise that by itself: {@code SubprocessRegistry}'s shutdown hook, or the
     * terminal's SIGINT to the whole process group, can kill the adapter before {@code
     * SessionTeardown} starts, and its death would then read as a finding (#437). The verdicts are
     * still computed, since the caller returns the code either way.
     *
     * <p>Static, with plain booleans, so the precedence can be tested without a live session. It
     * takes the logger instead of holding one because this class obtains its logger only after
     * {@link LoggingSetup#configure} has run, and so must not keep one in a static field.
     *
     * @param shuttingDown whether the JVM is already shutting down, which while a run is live can
     *     only mean a signal ended it
     * @param lobbyDropped whether the lobby connection closed abruptly under the session
     * @param launchFailed whether the session's ICE adapter or game never came up; {@link
     *     MockClientLifecycle#launchFailed()}
     * @param adapterLost whether the ICE adapter died unaccounted for; {@link
     *     MockClientLifecycle#adapterLost()}
     * @param gameCrashed whether the game process died unaccounted for; {@link
     *     MockClientLifecycle#gameCrashed()}
     * @param log the configured logger, for the single line naming what is reported
     * @return the code {@code run} should exit with, or {@link ExitCodes#OK} if nothing was found
     */
    static int sessionExitCode(
            final boolean shuttingDown,
            final boolean lobbyDropped,
            final boolean launchFailed,
            final boolean adapterLost,
            final boolean gameCrashed,
            final Logger log) {
        Logger verdict = shuttingDown ? NOPLogger.NOP_LOGGER : log;
        if (lobbyDropped) {
            verdict.warn("lobby connection dropped unexpectedly");
            return ExitCodes.RUNTIME;
        }
        if (launchFailed) {
            verdict.warn(
                    "the ICE adapter or game never came up; reporting it in this run's exit code");
            return ExitCodes.RUNTIME;
        }
        if (adapterLost) {
            verdict.warn("the ICE adapter died mid-session; reporting it in this run's exit code");
            return ExitCodes.ADAPTER_LOST;
        }
        if (gameCrashed) {
            verdict.warn(
                    "the game process died unexpectedly; reporting it in this run's exit code");
            return ExitCodes.GAME_CRASHED;
        }
        return ExitCodes.OK;
    }

    /**
     * Shutdown-hook body: always run the coordinated session teardown — even when the lobby is
     * already disconnected, later-registered handles (the game at launch, the adapter via R38/R59b)
     * may still need tearing down. Only the "shutdown signal" line is guarded, so normal exits stay
     * quiet.
     *
     * @param session the live lobby session, checked only to keep normal exits quiet
     * @param teardown the session's teardown, shared with the FSM path (R59b)
     * @param log logger for the single "shutdown signal received" line
     */
    private static void teardownOnShutdown(
            final LobbySession session, final SessionTeardown teardown, final Logger log) {
        if (!session.isDisconnected()) {
            log.info("shutdown signal received; tearing down session");
        }
        teardown.run();
    }
}
