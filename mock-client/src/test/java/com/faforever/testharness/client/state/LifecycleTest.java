package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.lobby.AccessToken;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.lobby.TokenSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LifecycleTest {
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
    void happyPath() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");

        TokenSource source =
                () -> CompletableFuture.completedFuture(new AccessToken("0123", Long.MAX_VALUE));

        MockClientLifecycle lifecycle = new MockClientLifecycle(lobby, handshake, source);
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        ObjectNode msg = MAPPER.createObjectNode();

        server.pollReceived(2, TimeUnit.SECONDS);
        server.broadcastText("{\"command\":\"session\",\"session\":50}");
        server.pollReceived(2, TimeUnit.SECONDS);
        server.broadcastText(
                "{\"command\":\"welcome\",\"me\":{\"id\":3,\"login\":\"Rhiza\"},"
                        + "\"id\":3,\"login\":\"Rhiza\","
                        + "\"current_time\":\"1970-01-01T00:00:00+00:00\"}");
        lifecycle.performHandshake();
        assertEquals(ClientState.IDLE, lifecycle.getState());

        server.broadcastText("{\"command\":\"game_launch\"}");
        Thread.sleep(2000); // 2 seconds to wait for lifecycle to process change.
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());

        server.broadcastText("{\"command\":\"HostGame\"}");
        Thread.sleep(2000); // 2 seconds to wait for lifecycle to process change.
        assertEquals(ClientState.HOSTING, lifecycle.getState());
    }

    @Test
    void forcefulShutdown() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");

        TokenSource source =
                () -> CompletableFuture.completedFuture(new AccessToken("0123", Long.MAX_VALUE));

        MockClientLifecycle lifecycle = new MockClientLifecycle(lobby, handshake, source);
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.shutdown();
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }
}
