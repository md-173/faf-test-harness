package com.faforever.testharness.client.cli;

import com.faforever.testharness.client.config.MockClientCli;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.AuthenticationException;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.TokenSources;
import com.faforever.testharness.client.state.MockClientLifecycle;
import com.faforever.testharness.shared.logging.LoggingSetup;
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
 * Stub for the {@code run} subcommand. Eventually drives a full Mock Client session: authenticate
 * against the lobby, join the queue, spawn {@code faf-ice-adapter} and {@code mock-game}, run the
 * lifecycle FSM, and tear down on game-end.
 *
 * <p>For WBS-3.1.5.2 (CLI scaffolding) this validates config and logs a TODO line, returning {@link
 * ExitCodes#NOT_IMPLEMENTED} so CI cannot mistake the stub for a real success. The owning track is
 * responsible for replacing the body of {@link #call()} with the real lifecycle.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Run a full mock client session: authenticate, queue, play, teardown.")
public final class RunCommand implements Callable<Integer> {

    /** Logger for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(RunCommand.class);

    /** Maximum amount of seconds to wait for lobby server to establish connection. */
    private static final int SERVER_TIMEOUT = 5;

    /** Picocli auto-injects the root command so the stub can read the populated config. */
    @ParentCommand private MockClientCli parent;

    /** Picocli auto-injects the active {@link CommandSpec} for scoped error reporting. */
    @Spec private CommandSpec spec;

    /**
     * Validate the config, set up logging, log a TODO line, and exit with {@link
     * ExitCodes#NOT_IMPLEMENTED}. The real session loop is owned by a sibling track.
     *
     * @return {@link ExitCodes#NOT_IMPLEMENTED}
     */
    @Override
    public Integer call() {
        MockClientConfig config = parent.toValidatedConfig(spec);
        MockClientCli.applyLoggingProperties(config);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);

        TokenSource source = null;
        try {
            source = TokenSources.fromConfig(config);
        } catch (AuthenticationException e) {
            LOG.error("Could not configure tokens due to: {}", e.getMessage());
            System.out.println(
                    String.format("Could not configure tokens due to: %s", e.getMessage()));
            return ExitCodes.RUNTIME;
        }

        if (config.lobbyWebSocketUrl() == null) {
            System.out.println(
                    "Lobby websocket url (--lobby-websocket-url) required for run command");
            return ExitCodes.RUNTIME;
        } else if (config.uniqueId() == null) {
            System.out.println("Unique ID (--unique-id) required for run command");
            return ExitCodes.RUNTIME;
        }

        LobbyConnection lobby = new LobbyConnection(config.lobbyWebSocketUrl());
        try {
            lobby.connect().get(SERVER_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.error(
                    "Could not connect to lobby server due to {} ({})",
                    e.getMessage(),
                    e.getClass().getSimpleName());
            System.out.println("Could not connect to lobby server");
            return ExitCodes.RUNTIME;
        }

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, config.uniqueId(), "0.1", "faf-client");

        MockClientLifecycle lifecycle = new MockClientLifecycle(lobby, handshake);

        lifecycle.start(source);
        try {
            lifecycle.awaitHandshake();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error(
                    "Could not complete handshake with lobby server due to {} ({})",
                    e.getMessage(),
                    e.getClass().getSimpleName());
            System.out.println("Could not authenticate lobby server connection");
            return ExitCodes.RUNTIME;
        }

        lifecycle.shutdown();
        LOG.info("TODO: 'run' not implemented yet");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
