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
import com.faforever.testharness.client.process.LaunchIdentity;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                    5,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    Optional.empty(),
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

    // #211: these tests assert HOSTING/PLAYING without ever reaching TERMINATED, so
    // SessionTeardown never runs to reap the hanging "game"/"ICE adapter" subprocesses started
    // below (see the DummyGameLauncher/DummyIceLauncher javadoc for why they hang). Tracked here so
    // tearDown can terminate them itself.
    private final List<SubprocessManager> subprocesses = new ArrayList<>();

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
        for (SubprocessManager subprocess : subprocesses) {
            subprocess.terminate(Duration.ofSeconds(1));
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

    @Test
    void successfulHostLaunch() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        String node =
                "{\"method\": \"onGpgNetMessageReceived\","
                        + "\"params\": [\"GameState\", [\"Launching\"]]}";
        iceConn.fireNotification("onGpgNetMessageReceived", MAPPER.readTree(node));
        assertEquals(ClientState.PLAYING, lifecycle.getState());
    }

    @Test
    void successfulJoinLaunch() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new JoinGame(JOIN_GAME_MESSAGE));
        String node =
                "{\"method\": \"onGpgNetMessageReceived\","
                        + "\"params\": [\"GameState\", [\"Launching\"]]}";
        iceConn.fireNotification("onGpgNetMessageReceived", MAPPER.readTree(node));
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
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        ObjectNode node = MAPPER.createObjectNode().put("method", "onGpgNetMessageReceived");
        ArrayNode params =
                node.putArray("params").add("GameState").add(MAPPER.createArrayNode().add("Idle"));

        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        params.set(1, "Lobby");
        iceConn.fireNotification("onGpgNetMessageReceived", node);
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        params.set(1, "Ended");
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
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        String node =
                "{\"method\": \"onGpgNetMessageReceived\","
                        + "\"params\": [\"DifferentMessage\", []]}";

        iceConn.fireNotification("onGpgNetMessageReceived", MAPPER.readTree(node));
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
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));

        // Lacking chunks array
        String node = "{\"method\": \"onGpgNetMessageReceived\"," + "\"params\": [\"GameState\"]}";
        iceConn.fireNotification("onGpgNetMessageReceived", MAPPER.readTree(node));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        // Empty chunks array
        node = "{\"method\": \"onGpgNetMessageReceived\"," + "\"params\": [\"GameState\", []]}";
        iceConn.fireNotification("onGpgNetMessageReceived", MAPPER.readTree(node));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        // Empty params array
        node = "{\"method\": \"onGpgNetMessageReceived\"," + "\"params\": []}";
        iceConn.fireNotification("onGpgNetMessageReceived", MAPPER.readTree(node));
        assertEquals(ClientState.HOSTING, lifecycle.getState());
    }

    private class DummyIceAdapterConnection extends IceAdapterConnection {
        // Mirrors IceAdapterConnection's real fan-out: MockClientLifecycle registers more than one
        // handler under "onGpgNetMessageReceived" (#192's GameEnded consumer alongside the
        // GameState/Launching one), so a single-handler map would silently drop earlier handlers.
        private final Map<String, List<Consumer<JsonNode>>> notificationHandlers = new HashMap<>();

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
            notificationHandlers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(handler);
        }

        public void fireNotification(final String name, JsonNode value) {
            for (Consumer<JsonNode> handler : notificationHandlers.getOrDefault(name, List.of())) {
                handler.accept(value);
            }
        }

        @Override
        public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

        @Override
        public void close() {}
    }

    // #211: HOSTING now drives to TERMINATED on GameExited, so a subprocess that exits on its own
    // would race that transition against whatever HOSTING/PLAYING assertion the test is making.
    // "sort" with no arguments blocks on stdin EOF on both Windows and POSIX, keeping the process
    // alive for the test's duration; tearDown() reaps it via the outer class's subprocesses list.
    private class DummyGameLauncher extends MockGameLauncher {
        DummyGameLauncher(MockClientConfig config) {
            super(config);
        }

        @Override
        public SubprocessManager start(LaunchIdentity identity) throws MockGameLaunchException {
            try {
                SubprocessManager manager =
                        SubprocessManager.start(
                                new ProcessBuilder("sort"),
                                "DUMMY SUBPROCESS",
                                Duration.ofSeconds(5));
                subprocesses.add(manager);
                return manager;
            } catch (IOException e) {
                throw new MockGameLaunchException(e.getMessage());
            }
        }
    }

    private class DummyIceLauncher extends IceAdapterLauncher {
        DummyIceLauncher(MockClientConfig config) {
            super(config);
        }

        @Override
        public SubprocessManager start(LaunchIdentity identity) throws IceAdapterLaunchException {
            try {
                SubprocessManager manager =
                        SubprocessManager.start(
                                new ProcessBuilder("sort"),
                                "DUMMY SUBPROCESS",
                                Duration.ofSeconds(5));
                subprocesses.add(manager);
                return manager;
            } catch (IOException e) {
                throw new IceAdapterLaunchException(e.getMessage());
            }
        }
    }
}
