package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        MockClientLifecycle lifecycle = new MockClientLifecycle(lobby, handshake);
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new WelcomeReceived(null));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        lifecycle.post(new LaunchGame(null));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());

        lifecycle.post(new HostGame(null));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        lifecycle.post(new HostGame(null));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        lifecycle.post(new GameExited(0));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void forcefulShutdown() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");

        MockClientLifecycle lifecycle = new MockClientLifecycle(lobby, handshake);
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.shutdown();
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }
}
