package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.framing.Framedata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ScriptedWebSocketServer} itself: a frame left queued with no write interest,
 * the state Java-WebSocket's lost-write race leaves behind (#415), still reaches the client.
 */
final class ScriptedWebSocketServerTest {

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
                // already closed
            }
        }
        server.stop(1000);
    }

    @Test
    void strandedFrameStillReachesTheClient() throws Exception {
        BlockingQueue<JsonNode> probes = new LinkedBlockingQueue<>();
        lobby = new LobbyConnection(server.uri());
        lobby.registerHandler("probe", probes::add);
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        // Read two frames first. The first read after the handshake's write is followed by a no-op
        // doWrite that could pick up the probe on its own; the second read is not, so after it
        // only OP_WRITE on the key can write the probe.
        for (int i = 0; i < 2; i++) {
            lobby.send(MAPPER.createObjectNode().put("command", "hello"));
            server.pollReceived(5, TimeUnit.SECONDS);
        }

        // Queue the probe the way WebSocketImpl.send does, minus the write interest: the state the
        // selector's OP_READ leaves behind when it overwrites the sender's OP_WRITE.
        WebSocketImpl conn = (WebSocketImpl) server.getConnections().iterator().next();
        for (Framedata frame : conn.getDraft().createFrames("{\"command\":\"probe\"}", false)) {
            conn.outQueue.add(conn.getDraft().createBinaryFrame(frame));
        }

        ScriptedWebSocketServer.awaitWritten(conn, "probe");

        assertNotNull(probes.poll(5, TimeUnit.SECONDS), "the stranded probe never arrived");
    }
}
