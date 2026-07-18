package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.config.GameJoinConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the join-side lobby exchange (3.1.1.8): from IDLE, the FSM sends {@code game_join} for a
 * configured target game ID (lobby-protocol-spec.md §4.2 / §10.2). The {@code game_launch} response
 * is already covered as a unit test of R24 ({@link
 * com.faforever.testharness.client.lobby.GameLaunchHandlerTest}); this class only exercises the
 * outbound {@code game_join} send.
 */
final class GameJoinTest {
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

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        server.stop(1000);
    }

    /** Copies {@link #MINIMAL_CONFIG} with {@code joinConfig} overridden. */
    private static MockClientConfig configWithJoinConfig(Optional<GameJoinConfig> joinConfig) {
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
                MINIMAL_CONFIG.hostConfig(),
                joinConfig);
    }

    private MockClientLifecycle newLifecycle(Optional<GameJoinConfig> joinConfig) throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        MockClientConfig config = configWithJoinConfig(joinConfig);
        return new MockClientLifecycle(
                config,
                session,
                new DummyIceAdapterConnection(config.iceAdapterRpcPort()),
                new DummyGameLauncher(config),
                new DummyIceLauncher(config),
                new SessionTeardown(lobby));
    }

    @Test
    void idleSendsGameJoinForConfiguredTargetWithPassword() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameJoinConfig(42, Optional.of("s3cret"))));

        lifecycle.post(new WelcomeReceived(null));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        JsonNode sent = MAPPER.readTree(server.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("game_join", sent.get("command").asText());
        assertEquals(42, sent.get("uid").asInt());
        assertEquals("s3cret", sent.get("password").asText());
    }

    @Test
    void idleOmitsPasswordWhenNoneConfigured() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameJoinConfig(7, Optional.empty())));

        lifecycle.post(new WelcomeReceived(null));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        JsonNode sent = MAPPER.readTree(server.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("game_join", sent.get("command").asText());
        assertEquals(7, sent.get("uid").asInt());
        assertFalse(sent.has("password"), "password should be omitted, not sent as null");
    }

    @Test
    void idleSendsNothingWhenNoTargetGameConfigured() throws Exception {
        MockClientLifecycle lifecycle = newLifecycle(Optional.empty());

        lifecycle.post(new WelcomeReceived(null));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        assertThrows(AssertionError.class, () -> server.pollReceived(500, TimeUnit.MILLISECONDS));
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

        // The launch path registers the returned manager for teardown and chains on its exit
        // future, so the dummy must hand back a real (trivially short-lived) child process.
        @Override
        public SubprocessManager start() throws MockGameLaunchException {
            try {
                return SubprocessManager.start(
                        new ProcessBuilder("echo"), "DUMMY SUBPROCESS", Duration.ofSeconds(5));
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
        public SubprocessManager start() throws IceAdapterLaunchException {
            return null;
        }
    }
}
