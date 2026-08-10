package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.config.GameHostConfig;
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
    void happyPath() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
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
    void sendsGameHostOnIdleEntryWhenConfigured() throws Exception {
        GameHostConfig hostConfig =
                new GameHostConfig(
                        "Test game",
                        "scmp_007",
                        "faf",
                        "public",
                        Optional.empty(),
                        Optional.empty(),
                        false);
        MockClientLifecycle lifecycle =
                lifecycleWithConfig(configWithHostConfig(Optional.of(hostConfig)));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        String received = server.pollReceived(2, TimeUnit.SECONDS);
        JsonNode parsed = MAPPER.readTree(received);
        assertEquals("game_host", parsed.get("command").asText());
        assertEquals("Test game", parsed.get("title").asText());
    }

    @Test
    void doesNotSendGameHostWhenNotConfigured() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        assertThrows(
                AssertionError.class,
                () -> server.pollReceived(500, TimeUnit.MILLISECONDS),
                "no game_host should be sent when the mock client was not configured to host");
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
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        lifecycle.post(new Disconnected(null));
        // When a game has already started, communication occurs peer-to-peer and lobby server is
        // not needed. Disconnection does not cause termination.
        assertEquals(ClientState.PLAYING, lifecycle.getState());
    }

    /**
     * Copies {@link #MINIMAL_CONFIG} with {@code hostConfig} overridden — used by host-on-IDLE
     * tests that need a config distinct from the shared minimal fixture.
     */
    private static MockClientConfig configWithHostConfig(Optional<GameHostConfig> hostConfig) {
        return new MockClientConfig(
                MINIMAL_CONFIG.lobbyWebSocketUrl(),
                MINIMAL_CONFIG.oauthTokenUrl(),
                MINIMAL_CONFIG.oauthAuthEndpoint(),
                MINIMAL_CONFIG.oauthRedirectUri(),
                MINIMAL_CONFIG.oauthScopes(),
                MINIMAL_CONFIG.oauthClientId(),
                MINIMAL_CONFIG.oauthRefreshTokenFile(),
                MINIMAL_CONFIG.uniqueId(),
                MINIMAL_CONFIG.clientVersion(),
                MINIMAL_CONFIG.userAgent(),
                MINIMAL_CONFIG.uidBinaryPath(),
                MINIMAL_CONFIG.iceAdapterBinaryPath(),
                MINIMAL_CONFIG.mockGameBinaryPath(),
                MINIMAL_CONFIG.iceAdapterRpcPort(),
                MINIMAL_CONFIG.iceAdapterGpgNetPort(),
                MINIMAL_CONFIG.iceAdapterLobbyPort(),
                MINIMAL_CONFIG.iceAdapterGameId(),
                MINIMAL_CONFIG.logLevel(),
                MINIMAL_CONFIG.logFile(),
                MINIMAL_CONFIG.playerIdOverride(),
                MINIMAL_CONFIG.playerLogin(),
                hostConfig,
                MINIMAL_CONFIG.joinConfig());
    }

    private MockClientLifecycle lifecycleWithConfig(MockClientConfig config) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        return new MockClientLifecycle(
                config,
                session,
                new DummyIceAdapterConnection(config.iceAdapterRpcPort()),
                new DummyGameLauncher(config),
                new DummyIceLauncher(config),
                new SessionTeardown(lobby));
    }

    private MockClientLifecycle defaultLifecycle() {
        return lifecycleWithConfig(MINIMAL_CONFIG);
    }
}
