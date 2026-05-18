package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live smoke test against the FAF public test environment ({@code wss://lobby.faforever.xyz}).
 * Verifies the connect + ping/pong round trip required by WBS-2.2.10's acceptance criteria.
 *
 * <p>Tagged {@code integration} so it does <em>not</em> run under the default {@code ./gradlew
 * test} task. Use {@code ./gradlew :mock-client:integrationTest} to run it explicitly. This keeps
 * CI green when the {@code .xyz} environment is having a bad day and avoids spurious network
 * dependency in everyday development.
 *
 * <p>No auth is performed — the server accepts the WebSocket connection unauthenticated and
 * responds to {@code ping} on its own, which is exactly the lifecycle this transport owns.
 */
@Tag("integration")
final class LobbyConnectionLiveSmokeTest {

    private static final URI FAF_TEST_LOBBY = URI.create("wss://lobby.faforever.xyz");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void connectAndPingPongRoundTrip() throws Exception {
        LobbyConnection lobby = new LobbyConnection(FAF_TEST_LOBBY);

        CountDownLatch pongReceived = new CountDownLatch(1);
        AtomicReference<JsonNode> capturedPong = new AtomicReference<>();
        lobby.registerHandler(
                "pong",
                node -> {
                    capturedPong.set(node);
                    pongReceived.countDown();
                });

        try {
            lobby.connect().get(15, TimeUnit.SECONDS);

            ObjectNode ping = MAPPER.createObjectNode();
            ping.put("command", "ping");
            lobby.send(ping).get(5, TimeUnit.SECONDS);

            assertTrue(
                    pongReceived.await(10, TimeUnit.SECONDS),
                    "expected pong from " + FAF_TEST_LOBBY + " within 10s of sending ping");
            assertNotNull(capturedPong.get(), "pong handler ran but captured node is null");
            assertEquals("pong", capturedPong.get().get("command").asText());
        } finally {
            lobby.close().get(5, TimeUnit.SECONDS);
        }
    }
}
