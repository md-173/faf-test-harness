package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameHostSender}, run against the in-process {@link ScriptedWebSocketServer}
 * used by {@link LobbyConnectionTest}.
 */
final class GameHostSenderTest {

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
                // some tests close the underlying socket already
            }
        }
        server.stop(1000);
    }

    @Test
    void sendsGameHostBuiltEntirelyFromConfig() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        MockClientConfig config =
                configWithHostSettings("Custom Title", "scmp_016", "faf", "public");

        new GameHostSender(lobby).sendGameHost(config).get(2, TimeUnit.SECONDS);

        String received = server.pollReceived(2, TimeUnit.SECONDS);
        JsonNode parsed = MAPPER.readTree(received);
        assertEquals("game_host", parsed.get("command").asText());
        assertEquals("Custom Title", parsed.get("title").asText());
        assertEquals("scmp_016", parsed.get("mapname").asText());
        assertEquals("faf", parsed.get("mod").asText());
        assertEquals("public", parsed.get("visibility").asText());
        assertFalse(parsed.has("password"), "password should be omitted, not sent as null");
    }

    @Test
    void sendsDifferentSettingsWhenConfigChanges() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        MockClientConfig config =
                configWithHostSettings("Another Game", "scmp_003", "ladder1v1", "friends");

        new GameHostSender(lobby).sendGameHost(config).get(2, TimeUnit.SECONDS);

        String received = server.pollReceived(2, TimeUnit.SECONDS);
        JsonNode parsed = MAPPER.readTree(received);
        assertTrue(received.endsWith("\n"), "expected newline-terminated frame, got: " + received);
        assertEquals("Another Game", parsed.get("title").asText());
        assertEquals("scmp_003", parsed.get("mapname").asText());
        assertEquals("ladder1v1", parsed.get("mod").asText());
        assertEquals("friends", parsed.get("visibility").asText());
    }

    private static MockClientConfig configWithHostSettings(
            final String title, final String map, final String mod, final String visibility) {
        return new MockClientConfig(
                java.net.URI.create("wss://lobby.faforever.xyz"),
                java.net.URI.create("https://hydra.faforever.xyz/oauth2/token"),
                java.net.URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                java.net.URI.create("http://127.0.0.1"),
                "openid offline lobby",
                "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                "test-refresh-token",
                null,
                "00000000-0000-0000-0000-000000000000",
                java.nio.file.Path.of("/bin/faf-ice-adapter"),
                java.nio.file.Path.of("/bin/mock-game"),
                7236,
                7237,
                7238,
                0,
                "INFO",
                Optional.empty(),
                OptionalInt.empty(),
                "mock-client",
                title,
                map,
                mod,
                visibility);
    }
}
