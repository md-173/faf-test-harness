package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.lobby.message.AskSessionMessage;
import com.faforever.testharness.client.lobby.message.AuthMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LobbyMessageSender}. Each typed outbound record is encoded and pushed
 * through a live {@link LobbyConnection} pointed at the in-process {@link ScriptedWebSocketServer};
 * the assertion compares the bytes the server received against a fixture from {@code
 * src/test/resources/lobby/outbound/}.
 */
final class LobbyMessageSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private LobbyMessageSender sender;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        sender = new LobbyMessageSender(lobby, MAPPER);
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
    void encodesAskSession() throws Exception {
        sender.send(new AskSessionMessage("0.11.16", "faf-client")).get(2, TimeUnit.SECONDS);

        JsonNode received = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        JsonNode expected = MAPPER.readTree(loadFixture("lobby/outbound/ask_session.json"));
        assertEquals(expected, received);
    }

    @Test
    void encodesAuth() throws Exception {
        sender.send(
                        new AuthMessage(
                                "eyJhbGciOiJSUzI1NiJ9.test.signature",
                                "7d04beb8-d4b8-40f5-8464-a9efa8546728",
                                812469452L))
                .get(2, TimeUnit.SECONDS);

        JsonNode received = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        JsonNode expected = MAPPER.readTree(loadFixture("lobby/outbound/auth.json"));
        assertEquals(expected, received);
    }

    @Test
    void blankRequiredFieldsRejectedAtConstruction() {
        // The records' canonical constructors enforce shape — the sender never sees an empty
        // payload because the record itself refuses to exist.
        assertThrows(IllegalArgumentException.class, () -> new AskSessionMessage("", "faf-client"));
        assertThrows(IllegalArgumentException.class, () -> new AskSessionMessage("1.0", null));
        assertThrows(IllegalArgumentException.class, () -> new AuthMessage("", "uid", 1L));
        assertThrows(IllegalArgumentException.class, () -> new AuthMessage("token", "  ", 1L));
    }

    @Test
    void wireFrameIsNewlineTerminated() throws Exception {
        // The connection appends \n per ws_bridge_rs compatibility — the sender must inherit
        // that behaviour and not produce a bare-JSON frame.
        sender.send(new AskSessionMessage("0.11.16", "faf-client")).get(2, TimeUnit.SECONDS);
        String raw = server.pollReceived(2, TimeUnit.SECONDS);
        assertEquals('\n', raw.charAt(raw.length() - 1));
    }

    private static String loadFixture(final String classpathPath) throws Exception {
        Path p =
                Path.of(
                        LobbyMessageSenderTest.class
                                .getClassLoader()
                                .getResource(classpathPath)
                                .toURI());
        return Files.readString(p);
    }
}
