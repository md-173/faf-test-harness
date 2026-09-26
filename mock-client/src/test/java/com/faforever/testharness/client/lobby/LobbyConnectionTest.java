package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectEvent;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectReason;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
        logAppender =
                new ListAppender<>() {
                    @Override
                    protected void append(final ILoggingEvent event) {
                        // Fixes the event's MDC on the logging thread; Logback reads it lazily.
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        // WebSocket callbacks can append while the test thread reads captured events.
        logAppender.list = new CopyOnWriteArrayList<>();
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
    void sendIsLoggedOnBothSidesWithCredentialsRedacted() throws Exception {
        // The outbound half of the exchange (#268). Two lines, because send() only hands the frame
        // to the send chain and the write completes later on the HttpClient executor — without
        // both, a timeout waiting for a frame the client believed it sent cannot separate "never
        // reached send()" from "queued and the write never completed".
        //
        // The redaction is the part that must not regress: the real auth frame carries a live
        // access token, and these logs are uploaded as CI artifacts on failure.
        Logger connectionLogger = (Logger) LoggerFactory.getLogger(LobbyConnection.class);
        Level original = connectionLogger.getLevel();
        connectionLogger.setLevel(Level.DEBUG);
        try {
            lobby = new LobbyConnection(server.uri());
            lobby.connect().get(5, TimeUnit.SECONDS);
            server.awaitFirstClient();

            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("command", "auth");
            msg.put("token", "super-secret-access-token");
            msg.put("unique_id", "uid-1");
            lobby.send(msg).get(2, TimeUnit.SECONDS);
            server.pollReceived(2, TimeUnit.SECONDS);

            List<String> sendLines =
                    logAppender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .filter(
                                    m ->
                                            m.startsWith("lobby sending frame")
                                                    || m.startsWith("lobby sent frame"))
                            .toList();

            assertTrue(
                    sendLines.stream().anyMatch(m -> m.startsWith("lobby sending frame")),
                    "the hand-off to the send chain must be logged: " + sendLines);
            assertTrue(
                    sendLines.stream().anyMatch(m -> m.startsWith("lobby sent frame")),
                    "the completed write must be logged: " + sendLines);
            assertTrue(
                    sendLines.stream().noneMatch(m -> m.contains("super-secret-access-token")),
                    "a credential reached the log: " + sendLines);
            assertTrue(
                    sendLines.stream().allMatch(m -> m.contains("<redacted>")),
                    "the token field must be replaced, not dropped: " + sendLines);
            // Redaction must not damage the rest of the frame, nor the frame actually sent.
            assertTrue(
                    sendLines.stream().allMatch(m -> m.contains("uid-1")),
                    "non-sensitive fields must survive redaction: " + sendLines);
        } finally {
            connectionLogger.setLevel(original);
        }
    }

    @Test
    void sendKeepsTheRealTokenOnTheWire() throws Exception {
        // The other half: redaction is for the log only. If it ever mutated the outgoing frame,
        // every authentication would break, so this pins the wire format against that.
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("command", "auth");
        msg.put("token", "super-secret-access-token");
        lobby.send(msg).get(2, TimeUnit.SECONDS);

        JsonNode onTheWire = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("super-secret-access-token", onTheWire.get("token").asText());
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
    void handlersAndHandshakeRunUnderTheLabelCapturedAtConstruction() throws Exception {
        // WBS-4.3.3: several clients share one JVM, so the JDK threads that run this connection's
        // callbacks carry its own label. The handshake continuation is checked through its log
        // line, which is the only thing it emits.
        MDC.put(LoggingSetup.INSTANCE_MDC_KEY, "B");
        try {
            lobby = new LobbyConnection(server.uri());
        } finally {
            MDC.remove(LoggingSetup.INSTANCE_MDC_KEY);
        }
        CompletableFuture<String> seen = new CompletableFuture<>();
        lobby.registerHandler(
                "session", node -> seen.complete(MDC.get(LoggingSetup.INSTANCE_MDC_KEY)));
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText("{\"command\":\"session\",\"session\":42}");

        assertEquals("B", seen.get(2, TimeUnit.SECONDS));
        ILoggingEvent connected =
                logAppender.list.stream()
                        .filter(
                                e ->
                                        e.getFormattedMessage()
                                                .startsWith("lobby WebSocket connected"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("B", connected.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY));
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

    /**
     * Closing a connection the server has already closed completes at once (WBS-3.1.2.6-fix, #454).
     * The JDK answers the server's close itself and shuts its output, so a second close frame
     * failed with "Output closed", which teardown logged as a WARN on every session whose lobby had
     * already gone.
     */
    @Test
    void closeAfterTheServerClosedCompletesQuietly() throws Exception {
        lobby = new LobbyConnection(server.uri());
        CountDownLatch disconnected = new CountDownLatch(1);
        lobby.onDisconnect(event -> disconnected.countDown());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.closeAllClean(1000, "bye");
        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect listener never fired");
        // The server's side ends once it has the client's answer, and the JDK closes its output
        // as it sends that answer.
        server.awaitClose(2, TimeUnit.SECONDS);

        lobby.close().get(2, TimeUnit.SECONDS);
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
        // The JDK reports a drop either as an error or as a close with code 1006, which a peer
        // never sends; both are the drop they are, not a clean close (#473).
        assertEquals(DisconnectReason.ABRUPT_CLOSE, captured.get().reason(), "" + captured.get());
    }

    /**
     * A lobby that sends nothing for the silence limit counts as dropped (#485). The JDK can lose a
     * drop that lands while a frame is being handled, reporting neither a close nor an error, and
     * faf-server pings every connection every 45 s, so silence is what is left to go on.
     */
    @Test
    void aSilentLobbyIsTreatedAsDropped() throws Exception {
        lobby = withSilenceLimit(Duration.ofSeconds(1));
        CompletableFuture<DisconnectEvent> disconnect = new CompletableFuture<>();
        lobby.onDisconnect(disconnect::complete);
        long start = System.nanoTime();
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        DisconnectEvent event = disconnect.get(10, TimeUnit.SECONDS);
        long waited = System.nanoTime() - start;

        assertEquals(DisconnectReason.ABRUPT_CLOSE, event.reason(), "" + event);
        assertInstanceOf(TimeoutException.class, event.error(), "" + event);
        assertEquals("lobby sent nothing for 1 s", event.error().getMessage());
        assertTrue(waited >= Duration.ofSeconds(1).toNanos(), "fired after " + waited + " ns");
        String warn = "lobby sent nothing for 1 s; treating the connection as dropped";
        assertTrue(
                logAppender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && warn.equals(e.getFormattedMessage())),
                "the silence must be logged as a WARN");
        // The socket is released rather than left open: the server sees the connection go, with
        // no Close frame.
        assertEquals(1006, server.awaitClose(5, TimeUnit.SECONDS));
    }

    /**
     * Lobby traffic, faf-server's pings included, keeps the connection alive, and the limit is
     * counted again from the last frame once the traffic stops (#485).
     */
    @Test
    void lobbyTrafficKeepsTheConnectionAlive() throws Exception {
        lobby = withSilenceLimit(Duration.ofSeconds(2));
        CompletableFuture<DisconnectEvent> disconnect = new CompletableFuture<>();
        lobby.onDisconnect(disconnect::complete);
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        // One and a half limits of traffic, a ping every twentieth of one, so only a stall of
        // nearly the whole limit could end it: the 2 s this class's other waits allow.
        for (int i = 0; i < 30; i++) {
            server.broadcastText("{\"command\":\"ping\"}");
            Thread.sleep(100);
        }
        assertFalse(
                disconnect.isDone(),
                "pings must keep the connection alive: " + disconnect.getNow(null));

        DisconnectEvent event = disconnect.get(10, TimeUnit.SECONDS);
        assertEquals(DisconnectReason.ABRUPT_CLOSE, event.reason(), "" + event);
    }

    /**
     * A frame the listener is still handling is not the lobby's silence (#485). A {@code
     * game_launch} runs the whole launch on the listener, which can take longer than the limit, and
     * the lobby's frames wait unread meanwhile.
     */
    @Test
    void aFrameStillBeingHandledIsNotSilence() throws Exception {
        Duration limit = Duration.ofSeconds(1);
        lobby = withSilenceLimit(limit);
        AtomicLong handled = new AtomicLong();
        lobby.registerHandler(
                "game_launch",
                frame -> {
                    try {
                        Thread.sleep(2 * limit.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    handled.set(System.nanoTime());
                });
        AtomicLong fired = new AtomicLong();
        CompletableFuture<DisconnectEvent> disconnect = new CompletableFuture<>();
        lobby.onDisconnect(
                event -> {
                    fired.set(System.nanoTime());
                    disconnect.complete(event);
                });
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText("{\"command\":\"game_launch\"}");
        // A later write makes the fixture write the frame even if its selector dropped the
        // write interest (#480); the ping is read once the handler returns.
        server.broadcastText("{\"command\":\"ping\"}");

        DisconnectEvent event = disconnect.get(10, TimeUnit.SECONDS);
        assertEquals(DisconnectReason.ABRUPT_CLOSE, event.reason(), "" + event);
        assertTrue(handled.get() != 0, "the connection was dropped while the frame was handled");
        assertTrue(
                fired.get() - handled.get() >= limit.toNanos(),
                "fired " + (fired.get() - handled.get()) + " ns after the handler returned");
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
    void multipleHandlersForSameCommandFireInRegistrationOrder() throws Exception {
        lobby = new LobbyConnection(server.uri());
        CountDownLatch both = new CountDownLatch(2);
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<JsonNode> firstSaw = new AtomicReference<>();
        lobby.registerHandler(
                "session",
                node -> {
                    firstSaw.set(node);
                    order.add("first");
                    both.countDown();
                });
        lobby.registerHandler(
                "session",
                node -> {
                    order.add("second");
                    both.countDown();
                });
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        server.broadcastText("{\"command\":\"session\",\"session\":42}");

        assertTrue(both.await(2, TimeUnit.SECONDS), "both handlers should fire");
        assertEquals(List.of("first", "second"), order, "handlers fire in registration order");
        assertEquals(42, firstSaw.get().get("session").asInt());
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

    /**
     * A connection to the scripted server with a silence limit short enough for a test (#485).
     *
     * @param limit how long the lobby may send nothing
     * @return the unconnected connection
     */
    private LobbyConnection withSilenceLimit(final Duration limit) {
        return new LobbyConnection(
                server.uri(),
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                LobbyConnection.DEFAULT_CONNECT_TIMEOUT,
                limit);
    }
}
