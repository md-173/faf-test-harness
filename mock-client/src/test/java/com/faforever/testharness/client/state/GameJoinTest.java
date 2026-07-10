package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.config.GameJoinConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

    private MockClientLifecycle newLifecycle(Optional<GameJoinConfig> joinConfig) throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        return new MockClientLifecycle(lobby, handshake, joinConfig);
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
}
