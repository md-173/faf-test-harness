package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.AuthenticationException;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.TokenSources;
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
 * idle (WBS-3.1.1.4). This is the user-facing umbrella over the three lobby pillars — transport
 * (3.1.1.1), auth (3.1.1.2), and welcome (3.1.1.3) — wired together via {@link LobbySession}.
 *
 * <p>The flow is {@code connect → ask_session → session → auth → welcome}, after which the client
 * logs its player id and blocks idle. It adds no protocol logic: the idle heartbeat is handled by
 * the transport, which auto-replies {@code pong} to lobby {@code ping}s.
 *
 * <p>A JVM shutdown hook closes the WebSocket cleanly on {@code Ctrl-C} / {@code SIGTERM}. The
 * process exit code then follows the signal per the JVM default (130 for SIGINT, 143 for SIGTERM) —
 * the close itself is clean, with no error logs.
 *
 * <p>Out of scope (later sprints): matchmaking, host/join, the lifecycle FSM (3.1.3.1), and
 * reconnect/recovery.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Connect to the lobby, authenticate, and sit idle until interrupted.")
public final class RunCommand implements Callable<Integer> {

    /** Bound on the WebSocket open before the handshake can start. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** Bound on the {@code ask_session → welcome} exchange once connected. */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /** Bound on the clean WebSocket close performed by the shutdown hook. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Picocli auto-injects the root command so the subcommand can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Validate config, open the lobby session, authenticate, and block idle until the connection
     * drops or a shutdown signal arrives.
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

        // Graceful shutdown on Ctrl-C / SIGTERM: close the socket cleanly. No-op if the session has
        // already disconnected (e.g. a server-initiated close that let call() return normally), so
        // a normal exit doesn't emit a spurious "shutdown signal" line.
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> closeOnShutdown(session, log), "mc-shutdown"));

        SessionState me;
        try {
            me = session.connectAndAuthenticate(tokens, CONNECT_TIMEOUT, HANDSHAKE_TIMEOUT);
        } catch (TimeoutException e) {
            log.error("lobby session timed out before welcome; closing");
            awaitQuietClose(session);
            return ExitCodes.RUNTIME;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("lobby session failed: {}", cause.getMessage());
            awaitQuietClose(session);
            return ExitCodes.RUNTIME;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            awaitQuietClose(session);
            return ExitCodes.RUNTIME;
        }

        log.info(
                "mock client idle as player id={} login={}; press Ctrl-C to exit",
                me.id(),
                me.login());

        // Idle: the transport auto-pongs lobby pings, so we just wait for the connection to end.
        try {
            LobbyConnection.DisconnectEvent event = session.awaitDisconnect();
            if (event != null && event.reason() == LobbyConnection.DisconnectReason.ABRUPT_CLOSE) {
                log.warn("lobby connection dropped unexpectedly");
                return ExitCodes.RUNTIME;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ExitCodes.OK;
    }

    /**
     * Shutdown-hook body: close the connection cleanly unless the session is already gone.
     *
     * @param session the live lobby session to close
     * @param log logger for the single "shutdown signal received" line
     */
    private static void closeOnShutdown(final LobbySession session, final Logger log) {
        if (session.isDisconnected()) {
            return;
        }
        log.info("shutdown signal received; closing lobby connection");
        awaitQuietClose(session);
    }

    /**
     * Close the session and wait briefly for the close to complete, swallowing close failures.
     *
     * @param session the lobby session to close
     */
    private static void awaitQuietClose(final LobbySession session) {
        try {
            session.close().get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Best-effort close on a failing/teardown path; nothing actionable to do.
        }
    }
}
