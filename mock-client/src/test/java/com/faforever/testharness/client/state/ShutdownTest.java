package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ShutdownTest {
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

    private static final JsonNode HOST_GAME_MESSAGE;
    private static final JsonNode JOIN_GAME_MESSAGE;

    // Static initialization block to build JsonNode messages.
    static {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        HOST_GAME_MESSAGE = node;

        node = MAPPER.createObjectNode().put("command", "JoinGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("test").add(1));
        JOIN_GAME_MESSAGE = node;
    }

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some tests close the underlying socket already
            }
        }
        server.stop(1000);
    }

    @Test
    void gameExit() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // Process lasts 2 seconds to ensure all transitions reached.
        DummyGameLauncher gameLauncher =
                new DummyGameLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "2"));
        // Launches long running processes
        DummyIceLauncher iceLauncher =
                new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        Thread.sleep(4000); // Ensure game binary has time to exit (4s > 2s).
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        // Sanity check that subprocesses have been created.
        assertTrue(gameLauncher.getSubprocess() != null);
        assertTrue(iceLauncher.getSubprocess() != null);
        // Game binary should have exited
        assertTrue(!gameLauncher.getSubprocess().isAlive());
        // Ice adapter binary should have been torn down.
        assertTrue(!iceLauncher.getSubprocess().isAlive());
    }

    @Test
    void requestedShutdown() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // Launches long running processes
        DummyGameLauncher gameLauncher =
                new DummyGameLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        DummyIceLauncher iceLauncher =
                new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        lifecycle.shutdown();
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        // Sanity check that subprocesses have been created.
        assertTrue(gameLauncher.getSubprocess() != null);
        assertTrue(iceLauncher.getSubprocess() != null);
        // Game binary should have been torn down.
        assertTrue(!gameLauncher.getSubprocess().isAlive());
        // Ice adapter binary should have been torn down.
        assertTrue(!iceLauncher.getSubprocess().isAlive());
    }

    @Test
    void disconnection() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // Process lasts 4 seconds to ensure lobby close timeout can occur (4s > 2s).
        DummyGameLauncher gameLauncher =
                new DummyGameLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "4"));
        // Launches long running processes
        DummyIceLauncher iceLauncher =
                new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        lobby.close().get(2, TimeUnit.SECONDS);
        // Teardown takes some time as it waits to close lobby, but it is already closed.
        lifecycle.stateReached(ClientState.TERMINATED).get();

        // Sanity check that subprocesses have been created.
        assertTrue(gameLauncher.getSubprocess() != null);
        assertTrue(iceLauncher.getSubprocess() != null);
        // Game binary should have been torn down.
        assertTrue(!gameLauncher.getSubprocess().isAlive());
        // Ice adapter binary should have been torn down.
        assertTrue(!iceLauncher.getSubprocess().isAlive());
    }
}
