package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PlayingTransitionTest {
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
    void successfulLaunch() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, session, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        ObjectNode node = MAPPER.createObjectNode().put("header", "GameState");
        node.putArray("chunks").add("Launching");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.PLAYING, lifecycle.getState());
    }

    @Test
    void otherGameStatesIgnored() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, session, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        ObjectNode node = MAPPER.createObjectNode().put("header", "GameState");
        ArrayNode gameState = node.putArray("chunks").add("Idle");

        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        gameState.set(0, "Lobby");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        gameState.set(0, "Ended");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());
    }

    @Test
    void otherGpgNetMessagesIgnored() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, session, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        ObjectNode node = MAPPER.createObjectNode().put("header", "DifferentCommand");
        node.putArray("chunks");

        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());
    }

    @Test
    void malformedGpgNetMessageHandledSilently() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, session, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));

        // Typo on purpose (head instead of header)
        ObjectNode node = MAPPER.createObjectNode().put("head", "GameState");
        node.putArray("chunks");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        // Lacking chunks array
        node = MAPPER.createObjectNode().put("header", "GameState");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        // Empty chunks array
        node = MAPPER.createObjectNode().put("header", "GameState");
        node.putArray("chunks");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());
    }

    private class DummyIceAdapterConnection extends IceAdapterConnection {
        // One handler per notification is enough for this test.
        private Map<String, Consumer<JsonNode>> notificationHandlers = new HashMap<>();

        DummyIceAdapterConnection(int port) {
            super(port);
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<JsonNode> call(final String method, final Object... params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void registerNotification(final String name, final Consumer<JsonNode> handler) {
            notificationHandlers.put(name, handler);
        }

        public void fireNotification(final String name, JsonNode value) {
            Consumer<JsonNode> handler = notificationHandlers.get(name);
            if (handler != null) {
                handler.accept(value);
            }
        }

        @Override
        public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

        @Override
        public void close() {}
    }

    private class DummyGameLauncher extends MockGameLauncher {
        DummyGameLauncher(MockClientConfig config) {
            super(config);
        }

        @Override
        public SubprocessManager start() throws MockGameLaunchException {
            return null;
        }
    }

    private class DummyIceLauncher extends IceAdapterLauncher {
        DummyIceLauncher(MockClientConfig config) {
            super(config);
        }

        @Override
        public SubprocessManager start() throws IceAdapterLaunchException {
            return null;
        }
    }
}
