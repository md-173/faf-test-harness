package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LifecycleSetupTest {
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
    void launchGameTransition() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
        assertTrue(gameLauncher.subprocessStarted());
        assertTrue(iceLauncher.subprocessStarted());

        Object[] lobbyInitMode = iceConn.receivedMessage("setLobbyInitMode");
        assertTrue(lobbyInitMode != null);
        assertEquals("normal", lobbyInitMode[0]);

        Object[] iceServers = iceConn.receivedMessage("setIceServers");
        assertTrue(iceServers != null);
        assertTrue(iceServers.length == 0);
    }

    @Test
    void hostGameTransition() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));

        Object[] hostGame = iceConn.receivedMessage("hostGame");
        assertTrue(hostGame != null);
        assertEquals("scmp_007", hostGame[0]);
    }

    @Test
    void joinGameTransition() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new JoinGame(JOIN_GAME_MESSAGE));

        Object[] joinGame = iceConn.receivedMessage("joinGame");
        assertTrue(joinGame != null);
        assertEquals("test", joinGame[0]);
        assertEquals(1, joinGame[1]);
    }

    @Test
    void gameLauncherFails() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG, true);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void iceAdapterLauncherFails() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, true);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void iceConnectionFailsOnConnection() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort(), true);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void iceConnectionCallFails() throws Exception {
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        iceConn.setupCallFail("setLobbyInitMode");
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG, lobby, handshake, iceConn, gameLauncher, iceLauncher);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    private class DummyIceAdapterConnection extends IceAdapterConnection {

        private final Map<String, Object[]> received = new HashMap<>();

        private final Set<String> failCalls = new HashSet<>();

        private final boolean failOnConnection;

        DummyIceAdapterConnection(int port) {
            this(port, false);
        }

        DummyIceAdapterConnection(int port, boolean failOnConnection) {
            super(port);
            this.failOnConnection = failOnConnection;
        }

        @Override
        public CompletableFuture<Void> connect() {
            if (failOnConnection) {
                return CompletableFuture.failedFuture(new IOException("Could not connect"));
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }

        /**
         * A {@link #call(final String method, final Object... params)} with {@code method} will
         * result in an exceptional future.
         */
        public void setupCallFail(String method) {
            failCalls.add(method);
        }

        @Override
        public CompletableFuture<JsonNode> call(final String method, final Object... params) {
            received.put(method, params);
            if (failCalls.remove(method)) {
                return CompletableFuture.failedFuture(new IOException("Bad call"));
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }

        @Override
        public void registerNotification(final String name, final Consumer<JsonNode> handler) {}

        @Override
        public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

        @Override
        public void close() {}

        public Object[] receivedMessage(String method) {
            return received.get(method);
        }
    }

    private class DummyGameLauncher extends MockGameLauncher {
        private boolean subprocessStarted = false;
        private final boolean throwException;

        DummyGameLauncher(MockClientConfig config) {
            this(config, false);
        }

        DummyGameLauncher(MockClientConfig config, boolean throwException) {
            super(config);
            this.throwException = throwException;
        }

        @Override
        public SubprocessManager start() throws MockGameLaunchException {
            subprocessStarted = true;
            if (throwException) {
                throw new MockGameLaunchException("Mock Game Launch failed");
            }
            try {
                return SubprocessManager.start(
                        new ProcessBuilder("echo"), "DUMMY SUBPROCESS", Duration.ofSeconds(5));
            } catch (IOException e) {
                throw new MockGameLaunchException(e.getMessage());
            }
        }

        public boolean subprocessStarted() {
            return subprocessStarted;
        }
    }

    private class DummyIceLauncher extends IceAdapterLauncher {
        private boolean subprocessStarted = false;
        private final boolean throwException;

        DummyIceLauncher(MockClientConfig config) {
            this(config, false);
        }

        DummyIceLauncher(MockClientConfig config, boolean throwException) {
            super(config);
            this.throwException = throwException;
        }

        @Override
        public SubprocessManager start() throws IceAdapterLaunchException {
            subprocessStarted = true;
            if (throwException) {
                throw new IceAdapterLaunchException("Ice Adapter Launch failed");
            }
            try {
                return SubprocessManager.start(
                        new ProcessBuilder("echo"), "DUMMY SUBPROCESS", Duration.ofSeconds(5));
            } catch (IOException e) {
                throw new IceAdapterLaunchException(e.getMessage());
            }
        }

        public boolean subprocessStarted() {
            return subprocessStarted;
        }
    }
}
