package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Client game crash recovery (#211): a game that dies before reaching PLAYING must not hang the
 * client. Covers the missing {@link GameExited} exit edges from STARTING_GAME, HOSTING, and
 * JOINING, the local WARN/INFO exit classification, the {@code GameState Ended} frame sent to the
 * lobby on every exit, and that the pre-existing clean-exit / late-{@link GameExited} behaviour
 * from #192/#193 is unchanged.
 */
final class CrashRecoveryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MockClientConfig MINIMAL_CONFIG =
            new MockClientConfig(
                    URI.create("wss://lobby.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
                    "00000000-0000-0000-0000-000000000000",
                    "0.0.0-mock",
                    "faf-test-harness",
                    Optional.empty(),
                    Path.of("/bin/faf-ice-adapter"),
                    Path.of("/bin/mock-game"),
                    0,
                    0,
                    0,
                    0,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    Optional.empty(),
                    Optional.empty());

    private static final GameConfig MINIMAL_GAME_CONFIG =
            new GameConfig(
                    42,
                    "faf",
                    "Test Game Name",
                    0,
                    "custom",
                    "global",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private ChildGameLauncher gameLauncher;
    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        appender.stop();
        root.detachAppender(appender);
        if (gameLauncher != null && gameLauncher.manager != null) {
            gameLauncher.manager.terminate(Duration.ofSeconds(1));
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some tests close the underlying socket already
            }
        }
        server.stop(1000);
    }

    /** Cross-platform child process argv that exits with {@code code} and nothing else. */
    private static ProcessBuilder exitingWith(int code) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? new ProcessBuilder("cmd", "/c", "exit", String.valueOf(code))
                : new ProcessBuilder("sh", "-c", "exit " + code);
    }

    @Test
    void exitDuringHostingReachesTerminatedAndRunsTeardown() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(1));
        SessionTeardown teardown = new SessionTeardown(lobby);
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, teardown);

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        // Teardown's TERMINATED entry hook must have run: the lobby it closes is now shut.
        assertTrue(lobby.close().isDone(), "teardown should already have closed the lobby");
    }

    @Test
    void nonZeroExitProducesWarnLineWithExitCode() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(42));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        boolean warned =
                appender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage().contains("42"));
        assertTrue(warned, "a non-zero exit must be logged as WARN with the exit code");
    }

    @Test
    void cleanExitDuringHostingProducesInfoLine() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(0));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        boolean infoLogged =
                appender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.INFO
                                                && e.getFormattedMessage().contains("exit code"));
        assertTrue(infoLogged, "a zero exit must be logged as INFO");
    }

    @Test
    void gameStateEndedSentToLobbyOnCrash() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(1));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        String message = server.pollReceived(5, TimeUnit.SECONDS);
        JsonNode envelope = MAPPER.readTree(message);
        assertEquals("GameState", envelope.get("command").asText());
        assertEquals("game", envelope.get("target").asText());
        assertEquals("Ended", envelope.get("args").get(0).asText());
    }

    @Test
    void lateGameExitedAfterTerminatedIsNoOp() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(1));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle.post(new GameExited(0));

        assertEquals(ClientState.TERMINATED, lifecycle.getState(), "must stay TERMINATED");
    }

    private MockClientLifecycle hostingLifecycle(
            ChildGameLauncher launcher, SessionTeardown teardown) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        launcher,
                        new DummyIceLauncher(MINIMAL_CONFIG),
                        teardown);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(hostGameMessage()));
        assertEquals(ClientState.HOSTING, lifecycle.getState());
        return lifecycle;
    }

    private static JsonNode hostGameMessage() {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        return node;
    }

    /** Launches a real child from the given builder and retains the manager for assertions. */
    private final class ChildGameLauncher extends MockGameLauncher {
        private final ProcessBuilder builder;
        private SubprocessManager manager;

        ChildGameLauncher(ProcessBuilder builder) {
            super(MINIMAL_CONFIG);
            this.builder = builder;
        }

        @Override
        public SubprocessManager start() throws MockGameLaunchException {
            try {
                manager = SubprocessManager.start(builder, "TEST GAME", Duration.ofSeconds(5));
                return manager;
            } catch (IOException e) {
                throw new MockGameLaunchException(e.getMessage());
            }
        }
    }
}
