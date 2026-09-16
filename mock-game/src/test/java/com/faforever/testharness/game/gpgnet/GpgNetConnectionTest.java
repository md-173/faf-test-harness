package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectEvent;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;

/**
 * Transport tests for {@link GpgNetConnection} against the in-process {@link ScriptedGpgNetServer}.
 */
final class GpgNetConnectionTest {

    /**
     * A port nothing can be listening on. Fixed and below every platform's ephemeral range, so no
     * {@code bind(0)} in this JVM can be assigned it — the connect-failure test below therefore
     * never asserts on a port it has released. Same fix as {@code IceAdapterConnectionTest} in
     * mock-client (WBS-3.1.4.1-fix, #287).
     */
    private static final int UNBOUND_PORT = 1;

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
        GpgNetConnection c = new GpgNetConnection(UNBOUND_PORT, 3, Duration.ofMillis(20));
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
     * A {@code close()} during the retry window abandons it at once and reports {@link
     * DisconnectReason#LOCAL_CLOSE}, never {@link DisconnectReason#CONNECT_FAILED}.
     *
     * <p>The budget (200 x 100 ms = 20 s) is far longer than the assertion window, so a regression
     * that only reads the flag after the loop fails by timing out. The sleep lets a couple of
     * attempts fail first so the check is exercised mid-loop, not just before attempt one; nothing
     * asserts on its length, so a slow scheduler weakens the test rather than failing it.
     *
     * <p>The reason assertion pins intent without reproducing the race it guards: with no socket
     * yet, {@code close()} fires {@code LOCAL_CLOSE} itself and usually wins. The interleaving
     * where the connect thread reports first is a few instructions wide and not reachable from a
     * test.
     */
    @Test
    void closeDuringRetryAbandonsTheConnectWindowAsLocalClose() throws Exception {
        GpgNetConnection c = new GpgNetConnection(UNBOUND_PORT, 200, Duration.ofMillis(100));
        conn = c;
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        c.onDisconnect(
                e -> {
                    event.set(e);
                    disconnected.countDown();
                });
        CompletableFuture<Void> connectFuture = c.connect();

        Thread.sleep(250);
        c.close();

        assertThrows(
                ExecutionException.class,
                () -> connectFuture.get(3, TimeUnit.SECONDS),
                "close() should abandon the retry window well inside its 20s budget");
        assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect listener should fire");
        assertEquals(
                DisconnectReason.LOCAL_CLOSE,
                event.get().reason(),
                "a deliberate close must not be reported as an unreachable adapter");
    }

    /**
     * An attempt against a listener that drops the connect, rather than refusing it, is bounded by
     * the connect timeout instead of the OS SYN-retry period (about 127 s on Linux).
     *
     * <p>A loopback listener that never accepts and whose accept queue is full drops further SYNs,
     * which is the one dropping target a test can build without firewall rules. Whether the OS
     * drops or refuses there is kernel behaviour, so the test probes for it: it runs wherever the
     * queue drops, is skipped where the kernel refuses instead (Windows), and fails on Linux, which
     * always drops, so CI can never quietly turn it into a skip. Takes about 2.5 s.
     */
    @Test
    void connectAttemptTimesOutWhenTheListenerDropsTheConnect() throws Exception {
        List<Socket> queued = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            if (!fillAcceptQueue(listener, queued)) {
                assertFalse(
                        OS.LINUX.isCurrentOs(),
                        "Linux should drop connects to a full accept queue; without that this test"
                                + " has nothing to measure");
                assumeTrue(false, "this OS refuses connects to a full accept queue");
            }
            GpgNetConnection c =
                    new GpgNetConnection(listener.getLocalPort(), 2, Duration.ofMillis(20));
            conn = c;
            CountDownLatch disconnected = new CountDownLatch(1);
            AtomicReference<DisconnectEvent> event = new AtomicReference<>();
            c.onDisconnect(
                    e -> {
                        event.set(e);
                        disconnected.countDown();
                    });

            CompletableFuture<Void> connectFuture = c.connect();

            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> connectFuture.get(10, TimeUnit.SECONDS),
                            "2 attempts x (1 s timeout + 20 ms) should fail well inside 10 s");
            assertInstanceOf(
                    SocketTimeoutException.class,
                    failure.getCause().getCause(),
                    "the last attempt should have failed on the connect timeout");
            assertTrue(disconnected.await(2, TimeUnit.SECONDS), "disconnect listener should fire");
            assertEquals(DisconnectReason.CONNECT_FAILED, event.get().reason());
        } finally {
            for (Socket socket : queued) {
                socket.close();
            }
        }
    }

    /**
     * Connect probes to {@code listener} until one times out, keeping the ones that connected so
     * the queue stays full.
     *
     * @return {@code true} if the queue now drops connects: a probe timed out after at least one
     *     connected, so the timeout is the queue filling rather than a slow first handshake
     */
    private static boolean fillAcceptQueue(final ServerSocket listener, final List<Socket> queued)
            throws IOException {
        for (int i = 0; i < 8; i++) {
            Socket probe = new Socket();
            try {
                probe.connect(listener.getLocalSocketAddress(), 500);
                queued.add(probe);
            } catch (SocketTimeoutException e) {
                probe.close();
                return !queued.isEmpty();
            } catch (IOException e) {
                probe.close();
                return false;
            }
        }
        return false;
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
