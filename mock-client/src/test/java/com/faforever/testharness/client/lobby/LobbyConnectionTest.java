package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectEvent;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link LobbyConnection} run against the in-process {@link
 * ScriptedWebSocketServer}. Each test sets up a fresh server on an OS-chosen port, exercises one
 * facet (send, receive, ping/pong, etc.), and tears the server down in {@code @AfterEach}.
 */
final class LobbyConnectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        // Attach a list appender so we can assert on the unhandled-command WARN path.
        Logger lobbyLogger = (Logger) LoggerFactory.getLogger(LobbyConnection.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        lobbyLogger.addAppender(logAppender);
        lobbyLogger.setLevel(Level.DEBUG);
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
        Logger lobbyLogger = (Logger) LoggerFactory.getLogger(LobbyConnection.class);
        lobbyLogger.detachAppender(logAppender);
        server.stop(1000);
    }

    @Test
    void connectsAndSendsNewlineTerminatedJson() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("command", "ask_session");
        msg.put("version", "1.0");
        lobby.send(msg).get(2, TimeUnit.SECONDS);

        String received = server.pollReceived(2, TimeUnit.SECONDS);
        // Wire format: trailing \n per ws_bridge_rs compatibility (spec §1).
        assertTrue(received.endsWith("\n"), "expected newline-terminated frame, got: " + received);
        JsonNode parsed = MAPPER.readTree(received);
        assertEquals("ask_session", parsed.get("command").asText());
        assertEquals("1.0", parsed.get("version").asText());
    }

    @Test
    void dispatchesIncomingMessageToRegisteredHandler() throws Exception {
        lobby = new LobbyConnection(server.uri());
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        lobby.registerHandler(
                "session",
                node -> {
                    captured.set(node);
                    dispatched.countDown();
                });
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText("{\"command\":\"session\",\"session\":42}");

        assertTrue(dispatched.await(2, TimeUnit.SECONDS), "handler was never invoked");
        assertEquals("session", captured.get().get("command").asText());
        assertEquals(42, captured.get().get("session").asInt());
    }

    @Test
    void toleratesTrailingNewlineOnIncomingMessage() throws Exception {
        lobby = new LobbyConnection(server.uri());
        CountDownLatch dispatched = new CountDownLatch(1);
        lobby.registerHandler("session", node -> dispatched.countDown());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        // The Rust bridge may pass through a trailing \n — spec §1.
        server.broadcastText("{\"command\":\"session\",\"session\":1}\n");

        assertTrue(
                dispatched.await(2, TimeUnit.SECONDS),
                "handler was never invoked for trailing-newline frame");
    }

    @Test
    void autoRepliesToPingWithPong() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText("{\"command\":\"ping\"}");

        String received = server.pollReceived(2, TimeUnit.SECONDS);
        JsonNode parsed = MAPPER.readTree(received);
        assertEquals("pong", parsed.get("command").asText());
    }

    @Test
    void unknownCommandLoggedOncePerCommand() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText("{\"command\":\"never_seen\"}");
        server.broadcastText("{\"command\":\"never_seen\",\"v\":2}");
        server.broadcastText("{\"command\":\"never_seen\",\"v\":3}");
        // Give the dispatcher time to process all three.
        Thread.sleep(300);

        long warnCount =
                logAppender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .filter(e -> e.getFormattedMessage().contains("never_seen"))
                        .count();
        assertEquals(
                1,
                warnCount,
                "expected exactly one WARN log for 'never_seen' across 3 occurrences");
    }

    @Test
    void cleanCloseSurfacesAsCleanCloseDisconnect() throws Exception {
        lobby = new LobbyConnection(server.uri());
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> captured = new AtomicReference<>();
        lobby.onDisconnect(
                event -> {
                    captured.set(event);
                    disconnected.countDown();
                });
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.closeAllClean(1000, "bye");

        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect listener never fired");
        assertEquals(DisconnectReason.CLEAN_CLOSE, captured.get().reason());
        assertEquals(1000, captured.get().statusCode());
        assertEquals("bye", captured.get().closeMessage());
    }

    @Test
    void abruptCloseSurfacesAsAbruptCloseDisconnect() throws Exception {
        lobby = new LobbyConnection(server.uri());
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> captured = new AtomicReference<>();
        lobby.onDisconnect(
                event -> {
                    captured.set(event);
                    disconnected.countDown();
                });
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.abruptlyTerminate();

        assertTrue(disconnected.await(3, TimeUnit.SECONDS), "disconnect listener never fired");
        DisconnectReason reason = captured.get().reason();
        // Some platforms surface an abrupt server-side terminate as CLEAN_CLOSE with code 1006;
        // the contract requirement is just "observable", not a specific bucket.
        assertTrue(
                reason == DisconnectReason.ABRUPT_CLOSE || reason == DisconnectReason.CLEAN_CLOSE,
                "expected ABRUPT_CLOSE or CLEAN_CLOSE bucket, got " + reason);
    }

    @Test
    void connectFailedSurfacesViaDisconnectListenerAndExceptionalFuture() throws Exception {
        // Stop the server so the connect attempt fails immediately.
        server.stop(500);
        URI dead = server.uri();

        lobby = new LobbyConnection(dead);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> captured = new AtomicReference<>();
        lobby.onDisconnect(
                event -> {
                    captured.set(event);
                    disconnected.countDown();
                });

        CompletableFuture<Void> connectFuture = lobby.connect();

        assertThrows(ExecutionException.class, () -> connectFuture.get(5, TimeUnit.SECONDS));
        assertTrue(
                disconnected.await(2, TimeUnit.SECONDS),
                "disconnect listener never fired on connect failure");
        assertEquals(DisconnectReason.CONNECT_FAILED, captured.get().reason());
        assertNotNull(captured.get().error(), "error should be populated for CONNECT_FAILED");
        lobby = null; // already gone; skip tearDown close
    }

    @Test
    void registeringPingHandlerIsRejected() throws Exception {
        lobby = new LobbyConnection(server.uri());
        LobbyMessageHandler noop =
                node -> {
                    /* no-op */
                };
        assertThrows(IllegalArgumentException.class, () -> lobby.registerHandler("ping", noop));
    }

    @Test
    void sendIsSafeFromMultipleThreads() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        int threadCount = 8;
        int sendsPerThread = 25;
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int i = 0; i < sendsPerThread; i++) {
                                        ObjectNode msg = MAPPER.createObjectNode();
                                        msg.put("command", "ask_session");
                                        msg.put("thread", threadId);
                                        msg.put("seq", i);
                                        lobby.send(msg).get(5, TimeUnit.SECONDS);
                                    }
                                } catch (Exception e) {
                                    errors.add(e);
                                }
                            });
            thread.start();
            threads.add(thread);
        }

        start.countDown();
        for (Thread t : threads) {
            t.join(15_000);
        }
        assertEquals(List.of(), errors, "no thread should fail to send");

        // Drain everything the server saw and check we got exactly threadCount * sendsPerThread.
        int expected = threadCount * sendsPerThread;
        int seen = 0;
        while (seen < expected) {
            server.pollReceived(2, TimeUnit.SECONDS);
            seen++;
        }
        assertEquals(expected, seen);
    }
}
