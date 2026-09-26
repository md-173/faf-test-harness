package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.SelectionKey;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.framing.Framedata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScriptedWebSocketServer} itself: a frame left queued with no write
 * interest, the state Java-WebSocket's lost-write race leaves behind (#415), still reaches the
 * client, and {@link ScriptedWebSocketServer#broadcastText} and {@link
 * ScriptedWebSocketServer#closeAllClean} return only once their frame is written.
 */
final class ScriptedWebSocketServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROBE = "{\"command\":\"probe\"}";

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
                // already closed
            }
        }
        server.stop(1000);
    }

    @Test
    void strandedFrameStillReachesTheClient() throws Exception {
        BlockingQueue<JsonNode> probes = connectProbeClient();

        // Read two frames first. The first read after the handshake's write is followed by a no-op
        // doWrite that could pick up the probe on its own; the second read is not, so after it
        // only OP_WRITE on the key can write the probe.
        for (int i = 0; i < 2; i++) {
            lobby.send(MAPPER.createObjectNode().put("command", "hello"));
            server.pollReceived(5, TimeUnit.SECONDS);
        }

        // Queue the probe the way WebSocketImpl.send does, minus the write interest: the state the
        // selector's OP_READ leaves behind when it overwrites the sender's OP_WRITE.
        WebSocketImpl conn = onlyConnection();
        for (Framedata frame : conn.getDraft().createFrames(PROBE, false)) {
            conn.outQueue.add(conn.getDraft().createBinaryFrame(frame));
        }
        assertEquals(
                0,
                conn.getSelectionKey().interestOps() & SelectionKey.OP_WRITE,
                "the probe must start with no write interest");
        assertFalse(conn.outQueue.isEmpty(), "the probe was written before it could be stranded");

        ScriptedWebSocketServer.awaitWritten(conn, "probe");

        assertNotNull(probes.poll(5, TimeUnit.SECONDS), "the stranded probe never arrived");
    }

    @Test
    void broadcastReturnsWithItsFrameWritten() throws Exception {
        BlockingQueue<JsonNode> probes = connectProbeClient();

        server.broadcastText(PROBE);

        // Without the wait, the selector has rarely written the frame by the time send() returns.
        assertTrue(onlyConnection().outQueue.isEmpty(), "broadcastText returned before the write");
        assertNotNull(probes.poll(5, TimeUnit.SECONDS), "the broadcast probe never arrived");
    }

    @Test
    void closeAllCleanReturnsWithItsCloseFrameWritten() throws Exception {
        connectProbeClient();
        WebSocketImpl conn = onlyConnection();

        server.closeAllClean(1000, "bye");

        // Without the wait, the selector has rarely written the close frame by the time close()
        // returns.
        assertTrue(conn.outQueue.isEmpty(), "closeAllClean returned before the write");
    }

    private BlockingQueue<JsonNode> connectProbeClient() throws Exception {
        BlockingQueue<JsonNode> probes = new LinkedBlockingQueue<>();
        lobby = new LobbyConnection(server.uri());
        lobby.registerHandler("probe", probes::add);
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
        return probes;
    }

    private WebSocketImpl onlyConnection() {
        return (WebSocketImpl) server.getConnections().iterator().next();
    }
}
