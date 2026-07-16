package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectEvent;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Transport tests for {@link GpgNetConnection} against the in-process {@link ScriptedGpgNetServer}.
 */
final class GpgNetConnectionTest {

    private ScriptedGpgNetServer server;
    private GpgNetConnection conn;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedGpgNetServer();
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

    /** Connect to the running fixture, with retry tuned short for fast tests. */
    private GpgNetConnection connect() throws Exception {
        GpgNetConnection c = new GpgNetConnection(server.port(), 5, Duration.ofMillis(20));
        c.connect().get(5, TimeUnit.SECONDS);
        server.awaitClient();
        return c;
    }

    @Test
    void connectFailsAfterRetriesWhenNothingListens() throws Exception {
        int deadPort = server.port();
        server.stop(); // free the port so connects are refused

        GpgNetConnection c = new GpgNetConnection(deadPort, 3, Duration.ofMillis(20));
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

    @Test
    void connectTwiceThrows() throws Exception {
        conn = connect();
        assertThrows(IllegalStateException.class, conn::connect);
    }

    @Test
    void sendEncodesFrameServerReceivesIt() throws Exception {
        conn = connect();

        conn.send(GpgNetFrame.of("CreateLobby", 0, 6112, "TestPlayer", 1234, 1));

        GpgNetFrame received = server.pollReceived(2, TimeUnit.SECONDS);
        assertEquals(GpgNetFrame.of("CreateLobby", 0, 6112, "TestPlayer", 1234, 1), received);
    }

    @Test
    void sendBeforeConnectThrows() {
        GpgNetConnection c = new GpgNetConnection(server.port(), 5, Duration.ofMillis(20));
        conn = c;
        assertThrows(IOException.class, () -> c.send(GpgNetFrame.of("GameEnded")));
    }

    @Test
    void inboundFrameIsDecodedAndHandedToConsumer() throws Exception {
        conn = connect();
        AtomicReference<GpgNetFrame> captured = new AtomicReference<>();
        CountDownLatch got = new CountDownLatch(1);
        conn.onFrame(
                frame -> {
                    captured.set(frame);
                    got.countDown();
                });

        server.sendFrame(GpgNetFrame.of("HostGame", "scmp_007"));

        assertTrue(got.await(2, TimeUnit.SECONDS), "consumer should receive the frame");
        assertEquals(GpgNetFrame.of("HostGame", "scmp_007"), captured.get());
    }

    @Test
    void readsBackToBackFramesInOneWrite() throws Exception {
        conn = connect();
        CountDownLatch both = new CountDownLatch(2);
        conn.onFrame(frame -> both.countDown());

        // Two frames concatenated in a single write, boundary recovered structurally (§2.1).
        byte[] first = GpgNetCodec.encode(GpgNetFrame.of("GameState", "Lobby"));
        byte[] second = GpgNetCodec.encode(GpgNetFrame.of("GameState", "Launching"));
        byte[] both2 = new byte[first.length + second.length];
        System.arraycopy(first, 0, both2, 0, first.length);
        System.arraycopy(second, 0, both2, first.length, second.length);
        server.sendRaw(both2);

        assertTrue(both.await(2, TimeUnit.SECONDS), "both back-to-back frames should dispatch");
    }

    @Test
    void throwingConsumerDoesNotKillReader() throws Exception {
        conn = connect();
        CountDownLatch secondArrived = new CountDownLatch(1);
        AtomicReference<GpgNetFrame> second = new AtomicReference<>();
        conn.onFrame(
                frame -> {
                    if ("HostGame".equals(frame.command())) {
                        throw new RuntimeException("boom");
                    }
                    second.set(frame);
                    secondArrived.countDown();
                });

        server.sendFrame(GpgNetFrame.of("HostGame", "scmp_007")); // consumer throws on this one
        server.sendFrame(
                GpgNetFrame.of("GameState", "Lobby")); // reader must survive to deliver this

        assertTrue(
                secondArrived.await(2, TimeUnit.SECONDS),
                "reader must survive a throwing consumer");
        assertEquals(GpgNetFrame.of("GameState", "Lobby"), second.get());
    }

    @Test
    void malformedFrameClosesConnectionWithoutResync() throws Exception {
        conn = connect();
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        conn.onDisconnect(
                e -> {
                    event.set(e);
                    disconnected.countDown();
                });

        // command "X" then chunk count = 11 (> max): the reader errors and closes, no resync
        // (§5.3).
        server.sendRaw(hex("01 00 00 00 58 0B 00 00 00"));

        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "malformed frame should disconnect");
        assertEquals(DisconnectReason.REMOTE_CLOSE, event.get().reason());
        // The socket must actually be closed, not just reported dead — otherwise send() keeps
        // succeeding into a connection nobody reads and the adapter's writes stall.
        assertThrows(IOException.class, () -> conn.send(GpgNetFrame.of("GameEnded")));
    }

    @Test
    void cleanRemoteCloseSurfacesAsDisconnect() throws Exception {
        conn = connect();
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        conn.onDisconnect(
                e -> {
                    event.set(e);
                    disconnected.countDown();
                });

        server.dropClient();

        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "remote close should disconnect");
        assertEquals(DisconnectReason.REMOTE_CLOSE, event.get().reason());
    }

    @Test
    void closeFiresLocalCloseDisconnectExactlyOnce() throws Exception {
        conn = connect();
        AtomicInteger fireCount = new AtomicInteger();
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        conn.onDisconnect(
                e -> {
                    event.set(e);
                    fireCount.incrementAndGet();
                    disconnected.countDown();
                });

        conn.close();

        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect should fire on close");
        assertEquals(DisconnectReason.LOCAL_CLOSE, event.get().reason());
        Thread.sleep(100); // allow any erroneous second fire to surface
        assertEquals(1, fireCount.get(), "disconnect listener should fire exactly once");
    }

    /** Parse a hex string (spaces ignored) into bytes. */
    private static byte[] hex(final String h) {
        String s = h.replaceAll("\\s", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
