package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * SIGTERM} (WBS-3.1.3.2) — subprocesses first, then connections; today only the lobby connection is
 * registered, and the game/adapter handles join via the FSM wiring (R59b). The resulting disconnect
 * event drives the FSM to TERMINATED. The process exit code then follows the signal per the JVM
 * default (130 for SIGINT, 143 for SIGTERM) — the close itself is clean, with no error logs.
 *
 * <p>Out of scope (later sprints): matchmaking/host/join initiation, and reconnect/recovery.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
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
     * @return {@link ExitCodes#OK} after a clean close; {@link ExitCodes#RUNTIME} if the session
     *     could not be established or the connection dropped unexpectedly
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
        MockClientLifecycle lifecycle = new MockClientLifecycle(config, session);
        SessionTeardown teardown = new SessionTeardown(connection);

        // Graceful shutdown on Ctrl-C / SIGTERM: run the coordinated teardown synchronously before
        // the JVM exits. The lobby close's disconnect event drives the FSM to TERMINATED, releasing
        // the main thread. No-op if the session has already disconnected (e.g. a server-initiated
        // close that let call() return normally), so a normal exit doesn't emit a spurious
        // "shutdown signal" line.
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> teardownOnShutdown(session, teardown, log), "mc-shutdown"));

        SessionState me;
        try {
            me = lifecycle.start(tokens).get(SETUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            lifecycle
                    .stateReached(ClientState.IDLE)
                    .get(FSM_SYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("lobby session timed out before welcome; closing");
            teardown.run();
            return ExitCodes.RUNTIME;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("lobby session failed: {}", cause.getMessage());
            teardown.run();
            return ExitCodes.RUNTIME;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            teardown.run();
            return ExitCodes.RUNTIME;
        }

        log.info(
                "mock client idle as player id={} login={}; press Ctrl-C to exit",
                me.id(),
                me.login());

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

        LobbyConnection.DisconnectEvent event = session.disconnectEvent().orElse(null);
        if (event != null && event.reason() == LobbyConnection.DisconnectReason.ABRUPT_CLOSE) {
            log.warn("lobby connection dropped unexpectedly");
            return ExitCodes.RUNTIME;
        }
        return ExitCodes.OK;
    }

    /**
     * Shutdown-hook body: run the coordinated session teardown unless the session is already gone.
     *
     * @param session the live lobby session, checked to keep normal exits quiet
     * @param teardown the session's teardown, shared with the FSM path (R59b)
     * @param log logger for the single "shutdown signal received" line
     */
    private static void teardownOnShutdown(
            final LobbySession session, final SessionTeardown teardown, final Logger log) {
        if (session.isDisconnected()) {
            return;
        }
        log.info("shutdown signal received; tearing down session");
        teardown.run();
    }
}
