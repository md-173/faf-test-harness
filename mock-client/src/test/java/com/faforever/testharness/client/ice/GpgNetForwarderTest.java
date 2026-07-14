package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GpgNetForwarder}, driving the forward path with a real {@link
 * IceAdapterConnection} against {@link ScriptedJsonRpcServer} and a real {@link LobbyConnection}
 * against {@link ScriptedWebSocketServer}.
 */
final class GpgNetForwarderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedWebSocketServer lobbyServer;
    private ScriptedJsonRpcServer adapterServer;
    private LobbyConnection lobby;
    private IceAdapterConnection adapter;
    private GpgNetForwarder forwarder;

    @BeforeEach
    void setUp() throws Exception {
        lobbyServer = new ScriptedWebSocketServer();
        lobbyServer.startAndAwait();
        adapterServer = new ScriptedJsonRpcServer();
        adapterServer.start();

        lobby = new LobbyConnection(lobbyServer.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        lobbyServer.awaitFirstClient();

        adapter =
                new IceAdapterConnection(
                        adapterServer.port(), 5, Duration.ofMillis(20), Duration.ofSeconds(2));
        adapter.connect().get(5, TimeUnit.SECONDS);
        adapterServer.awaitClient();

        forwarder = new GpgNetForwarder(lobby, adapter);
        forwarder.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (adapter != null) {
            adapter.close();
        }
        if (adapterServer != null) {
            adapterServer.stop();
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
        lobbyServer.stop(1000);
    }

    /** Sends one onGpgNetMessageReceived notification and returns the resulting lobby frame. */
    private String forward(final String header, final String chunksJson) throws Exception {
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onGpgNetMessageReceived\",\"params\":[\""
                        + header
                        + "\","
                        + chunksJson
                        + "]}\n");
        return lobbyServer.pollReceived(3, TimeUnit.SECONDS).strip();
    }

    /** A representative single-chunk frame: the envelope must match exactly. */
    @Test
    void gameStateFrameIsWrappedInGameEnvelope() throws Exception {
        String sent = forward("GameState", "[\"Lobby\"]");

        assertEquals(
                MAPPER.readTree(
                        "{\"command\":\"GameState\",\"target\":\"game\",\"args\":[\"Lobby\"]}"),
                MAPPER.readTree(sent));
    }

    /** Multiple mixed-type chunks pass through unchanged — no reordering, coercion, or drops. */
    @Test
    void multiChunkPlayerOptionPassesChunksThroughUnchanged() throws Exception {
        String sent = forward("PlayerOption", "[1,\"StartSpot\",2]");

        assertEquals(
                MAPPER.readTree(
                        "{\"command\":\"PlayerOption\",\"target\":\"game\","
                                + "\"args\":[1,\"StartSpot\",2]}"),
                MAPPER.readTree(sent));
    }

    /** start() is one-shot — a second call would forward every frame twice, so it throws. */
    @Test
    void startTwiceThrows() {
        assertThrows(IllegalStateException.class, forwarder::start);
    }

    /** Malformed notifications are logged and dropped; the forwarder keeps working. */
    @Test
    void malformedNotificationIsDroppedAndForwarderSurvives() throws Exception {
        // chunks missing.
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onGpgNetMessageReceived\","
                        + "\"params\":[\"GameState\"]}\n");
        // header not a string.
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onGpgNetMessageReceived\","
                        + "\"params\":[7,[\"Lobby\"]]}\n");
        // chunks not an array.
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onGpgNetMessageReceived\","
                        + "\"params\":[\"GameState\",\"Lobby\"]}\n");

        assertThrows(
                AssertionError.class,
                () -> lobbyServer.pollReceived(300, TimeUnit.MILLISECONDS),
                "malformed notifications must not produce a lobby frame");

        // A valid frame afterwards still forwards — the reader thread survived.
        String sent = forward("GameEnded", "[]");
        assertEquals(
                MAPPER.readTree("{\"command\":\"GameEnded\",\"target\":\"game\",\"args\":[]}"),
                MAPPER.readTree(sent));
    }
}
