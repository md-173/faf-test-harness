package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LifecycleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MockClientConfig MINIMAL_CONFIG =
            new MockClientConfig(
                    URI.create("wss://lobby.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    "test-refresh-token",
                    null,
                    "00000000-0000-0000-0000-000000000000",
                    Path.of("/bin/faf-ice-adapter"),
                    Path.of("/bin/mock-game"),
                    0,
                    0,
                    0,
                    0,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza");

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
    void happyPath() throws Exception {
        System.out.println(HOST_GAME_MESSAGE);
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new WelcomeReceived(null));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());

        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        lifecycle.post(new GameExited(0));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void forcefulShutdown() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.shutdown();
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void authFailure() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new AuthFailed(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void disconnection() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        lifecycle.post(new Disconnected(null));
        // When a game has already started, communication occurs peer-to-peer and lobby server is
        // not needed. Disconnection does not cause termination.
        assertEquals(ClientState.PLAYING, lifecycle.getState());
    }

    private class DummyIceAdapterConnection extends IceAdapterConnection {
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
        public void registerNotification(final String name, final Consumer<JsonNode> handler) {}

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

    private MockClientLifecycle defaultLifecycle() {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        return new MockClientLifecycle(
                MINIMAL_CONFIG,
                lobby,
                handshake,
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                new DummyGameLauncher(MINIMAL_CONFIG),
                new DummyIceLauncher(MINIMAL_CONFIG));
    }
}
