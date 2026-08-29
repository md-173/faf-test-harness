package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectEvent;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link IceAdapterConnection} against the in-process {@link ScriptedJsonRpcServer}.
 */
final class IceAdapterConnectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedJsonRpcServer server;
    private IceAdapterConnection conn;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedJsonRpcServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** Connect to the running fixture, with retry/timeout tuned short for fast tests. */
    private IceAdapterConnection connect() throws Exception {
        IceAdapterConnection c =
                new IceAdapterConnection(
                        server.port(), 5, Duration.ofMillis(20), Duration.ofSeconds(2));
        c.connect().get(5, TimeUnit.SECONDS);
        server.awaitClient();
        return c;
    }

    @Test
    void connectFailsAfterRetriesWhenNothingListens() throws Exception {
        int deadPort = server.port();
        server.stop(); // free the port so connects are refused

        IceAdapterConnection c =
                new IceAdapterConnection(deadPort, 3, Duration.ofMillis(20), Duration.ofSeconds(1));
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        c.onDisconnect(
                e -> {
                    event.set(e);
                    disconnected.countDown();
                });

        CompletableFuture<Void> connectFuture = c.connect();

        assertThrows(ExecutionException.class, () -> connectFuture.get(5, TimeUnit.SECONDS));
        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect listener should fire");
        assertEquals(DisconnectReason.CONNECT_FAILED, event.get().reason());
    }

    /**
     * {@code close()} must cut a connect-retry window short rather than let it run to exhaustion.
     *
     * <p>This is the shutdown-responsiveness path, not a tidiness check: the CLI's signal hook runs
     * {@code SessionTeardown} directly rather than through the state machine, and teardown closes
     * this connection — so a Ctrl-C while the adapter is still binding relies on the retry loop
     * noticing. The budget here (200 × 100 ms = 20 s) is far longer than the assertion window, so a
     * regression that only checks the flag after the loop finishes fails this test by timing out.
     */
    @Test
    void closeDuringRetryAbandonsTheConnectWindow() throws Exception {
        int deadPort = server.port();
        server.stop(); // free the port so connects are refused

        IceAdapterConnection c =
                new IceAdapterConnection(
                        deadPort, 200, Duration.ofMillis(100), Duration.ofSeconds(1));
        CompletableFuture<Void> connectFuture = c.connect();

        // Let a couple of attempts fail so the loop is genuinely mid-flight, then close.
        Thread.sleep(250);
        c.close();

        assertThrows(
                ExecutionException.class,
                () -> connectFuture.get(3, TimeUnit.SECONDS),
                "close() should abandon the retry window well inside its 20s budget");
    }

    /**
     * A {@code close()} during the retry window is reported as {@link
     * DisconnectReason#LOCAL_CLOSE}, never as {@link DisconnectReason#CONNECT_FAILED} — the adapter
     * was reachable or not, but either way we are the ones who stopped trying.
     *
     * <p>This pins intent; it does not reproduce the race it guards. With a 100 ms retry delay and
     * a 250 ms sleep, {@code close()} beats the sleeping connect thread every time, so the listener
     * fires from {@code close()}'s own {@code socket == null} path. The interleaving where the
     * connect thread reports first is a few instructions wide and not reachable from a test — but a
     * regression that hardcodes {@code CONNECT_FAILED} in that path would still be caught the
     * moment it ever won.
     */
    @Test
    void closeDuringRetryReportsLocalCloseNotConnectFailure() throws Exception {
        int deadPort = server.port();
        server.stop(); // free the port so connects are refused

        IceAdapterConnection c =
                new IceAdapterConnection(
                        deadPort, 200, Duration.ofMillis(100), Duration.ofSeconds(1));
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        c.onDisconnect(
                e -> {
                    event.set(e);
                    fired.countDown();
                });

        CompletableFuture<Void> connectFuture = c.connect();
        Thread.sleep(250);
        c.close();

        assertThrows(ExecutionException.class, () -> connectFuture.get(3, TimeUnit.SECONDS));
        assertTrue(fired.await(2, TimeUnit.SECONDS), "disconnect listener should fire");
        assertEquals(
                DisconnectReason.LOCAL_CLOSE,
                event.get().reason(),
                "a deliberate close must not be reported as an unreachable adapter");
    }

    @Test
    void connectTwiceThrows() throws Exception {
        conn = connect();
        assertThrows(IllegalStateException.class, conn::connect);
    }

    @Test
    void callSendsCompactJsonRpcRequest() throws Exception {
        conn = connect();

        conn.call("joinGame", "Alice", 1);

        JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("2.0", request.get("jsonrpc").asText());
        assertEquals("joinGame", request.get("method").asText());
        assertEquals("Alice", request.get("params").get(0).asText());
        assertEquals(1, request.get("params").get(1).asInt());
        assertEquals(1L, request.get("id").asLong());
    }

    @Test
    void callCompletesWithResultForMatchingId() throws Exception {
        conn = connect();

        CompletableFuture<JsonNode> result = conn.call("status");
        JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        server.send(jsonResult(request.get("id").asLong(), "{\"version\":\"1.2.3\"}"));

        assertEquals("1.2.3", result.get(2, TimeUnit.SECONDS).get("version").asText());
    }

    @Test
    void concurrentCallsCorrelateById() throws Exception {
        conn = connect();

        CompletableFuture<JsonNode> first = conn.call("a");
        CompletableFuture<JsonNode> second = conn.call("b");

        JsonNode r1 = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        JsonNode r2 = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        // Reply out of order to prove correlation is by id, not arrival order.
        server.send(jsonResult(r2.get("id").asLong(), "\"" + r2.get("method").asText() + "\""));
        server.send(jsonResult(r1.get("id").asLong(), "\"" + r1.get("method").asText() + "\""));

        assertEquals("a", first.get(2, TimeUnit.SECONDS).asText());
        assertEquals("b", second.get(2, TimeUnit.SECONDS).asText());
    }

    @Test
    void dispatchesNotificationWithEmbeddedBraces() throws Exception {
        conn = connect();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        CountDownLatch got = new CountDownLatch(1);
        conn.registerNotification(
                "onIceMsg",
                msg -> {
                    captured.set(msg);
                    got.countDown();
                });

        // The string value contains { and } — must not desync the reader (spec §2.1).
        server.send("{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,2,\"a}b{c\"]}");

        assertTrue(got.await(2, TimeUnit.SECONDS), "notification should dispatch");
        assertEquals("a}b{c", captured.get().get("params").get(2).asText());
    }

    @Test
    void readsBackToBackFramesWithoutDelimiter() throws Exception {
        conn = connect();
        CountDownLatch both = new CountDownLatch(2);
        conn.registerNotification("onConnected", msg -> both.countDown());

        // Two frames in one write, no newline/whitespace between them.
        server.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onConnected\",\"params\":[1]}"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"onConnected\",\"params\":[2]}");

        assertTrue(both.await(2, TimeUnit.SECONDS), "both back-to-back frames should dispatch");
    }

    @Test
    void multipleConsumersForSameNotificationAllFire() throws Exception {
        conn = connect();
        CountDownLatch both = new CountDownLatch(2);
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<JsonNode> firstSaw = new AtomicReference<>();
        conn.registerNotification(
                "onIceMsg",
                msg -> {
                    firstSaw.set(msg);
                    order.add("first");
                    both.countDown();
                });
        conn.registerNotification(
                "onIceMsg",
                msg -> {
                    order.add("second");
                    both.countDown();
                });

        server.send("{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,2,\"x\"]}");

        assertTrue(both.await(2, TimeUnit.SECONDS), "both consumers should fire");
        assertEquals(List.of("first", "second"), order, "consumers fire in registration order");
        assertEquals("x", firstSaw.get().get("params").get(2).asText());
    }

    @Test
    void throwingConsumerDoesNotStopOthers() throws Exception {
        conn = connect();
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger connLogger = ctx.getLogger(IceAdapterConnection.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        connLogger.addAppender(appender);
        try {
            CountDownLatch goodFired = new CountDownLatch(1);
            conn.registerNotification(
                    "onConnectionStateChanged",
                    msg -> {
                        throw new RuntimeException("boom");
                    });
            conn.registerNotification("onConnectionStateChanged", msg -> goodFired.countDown());

            server.send(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"onConnectionStateChanged\",\"params\":[]}");

            assertTrue(
                    goodFired.await(2, TimeUnit.SECONDS),
                    "a throwing consumer must not stop the next one from firing");

            // The throwing consumer is logged at WARN (AC #2). It runs before the good one on the
            // single reader thread, so by the time goodFired releases the event is already there.
            assertTrue(
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getLevel() == Level.WARN
                                                    && e.getFormattedMessage()
                                                            .contains("onConnectionStateChanged")
                                                    && e.getFormattedMessage().contains("threw")),
                    "a throwing consumer should be logged at WARN; events: " + appender.list);

            // The reader thread survived the throw: a follow-up call still round-trips.
            CompletableFuture<JsonNode> result = conn.call("status");
            JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
            server.send(jsonResult(request.get("id").asLong(), "true"));
            assertTrue(result.get(2, TimeUnit.SECONDS).asBoolean());
        } finally {
            connLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unknownNotificationDoesNotBreakConnection() throws Exception {
        conn = connect();

        // No handler registered — must be dropped, not crash the reader.
        server.send("{\"jsonrpc\":\"2.0\",\"method\":\"mystery\",\"params\":[]}");

        // The connection must still work: a follow-up call round-trips.
        CompletableFuture<JsonNode> result = conn.call("status");
        JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        server.send(jsonResult(request.get("id").asLong(), "true"));
        assertTrue(result.get(2, TimeUnit.SECONDS).asBoolean());
    }

    @Test
    void errorResponseCompletesWithIceRpcException() throws Exception {
        conn = connect();

        CompletableFuture<JsonNode> result = conn.call("bogus");
        JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        server.send(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,\"message\":\"Method Not Found\"},"
                        + "\"id\":"
                        + request.get("id").asLong()
                        + "}");

        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        IceRpcException rpc = assertInstanceOf(IceRpcException.class, thrown.getCause());
        assertEquals(-32601, rpc.code());
    }

    @Test
    void callTimesOutWhenNoResponse() throws Exception {
        IceAdapterConnection c =
                new IceAdapterConnection(
                        server.port(), 5, Duration.ofMillis(20), Duration.ofMillis(200));
        c.connect().get(5, TimeUnit.SECONDS);
        server.awaitClient();
        conn = c;

        CompletableFuture<JsonNode> result = c.call("status");
        server.pollReceived(2, TimeUnit.SECONDS); // request sent; deliberately never answered

        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    void disconnectFiresOnceAndFailsInFlightCalls() throws Exception {
        conn = connect();
        AtomicInteger fireCount = new AtomicInteger();
        CountDownLatch disconnected = new CountDownLatch(1);
        conn.onDisconnect(
                e -> {
                    fireCount.incrementAndGet();
                    disconnected.countDown();
                });

        CompletableFuture<JsonNode> inflight = conn.call("status");
        server.pollReceived(2, TimeUnit.SECONDS); // ensure the call is registered before the drop
        server.dropClient();

        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect should fire");
        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> inflight.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, thrown.getCause());

        Thread.sleep(100); // allow any erroneous second fire to surface
        assertEquals(1, fireCount.get(), "disconnect listener should fire exactly once");
    }

    @Test
    void closeFiresLocalCloseDisconnect() throws Exception {
        conn = connect();
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        conn.onDisconnect(
                e -> {
                    event.set(e);
                    disconnected.countDown();
                });

        conn.close();

        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect should fire on close");
        assertEquals(DisconnectReason.LOCAL_CLOSE, event.get().reason());
    }

    @Test
    void inboundRequestIsIgnored() throws Exception {
        conn = connect();

        // A method+id frame is an inbound request; the adapter never sends one, so ignore it.
        server.send("{\"jsonrpc\":\"2.0\",\"method\":\"someMethod\",\"params\":[],\"id\":99}");

        // The connection still works afterward.
        CompletableFuture<JsonNode> result = conn.call("status");
        JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        server.send(jsonResult(request.get("id").asLong(), "true"));
        assertTrue(result.get(2, TimeUnit.SECONDS).asBoolean());
    }

    @Test
    void noArgCallEmitsEmptyParamsArray() throws Exception {
        conn = connect();

        conn.call("status");

        JsonNode request = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertTrue(request.get("params").isArray());
        assertEquals(0, request.get("params").size());
    }

    private static String jsonResult(final long id, final String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"result\":" + resultJson + ",\"id\":" + id + "}";
    }
}
