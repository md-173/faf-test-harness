package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectEvent;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
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
import org.junit.jupiter.api.Timeout;

/**
 * {@link GpgNetConnection#close()} landing inside the connect window (WBS-3.2.2.1-fix, #330).
 *
 * <p>{@code runConnection} publishes the socket and only then reads the close flag, while {@code
 * close()} sets the flag and fires {@code LOCAL_CLOSE} itself only when it finds no socket. A close
 * between those two reads therefore saw a socket, left the event to the connect thread, and that
 * thread returned before the read loop without firing anything: the listener never ran.
 *
 * <p>The window is two instructions wide with no observable edge, so the test drives it through the
 * {@code socketPublished()} seam, calling {@code close()} on the connect thread exactly there,
 * instead of racing threads and hoping.
 *
 * <p>It waits on the listener, not the connect future, because the future is failed before the
 * disconnect fires. The exactly-once assertion confirms a single fire on this path; it does not
 * test the one-shot guard, since nothing else can fire here. The halves either side of the window
 * are not repeated: a close on a live connection is {@code GpgNetConnectionTest}'s {@code
 * closeFiresLocalCloseDisconnectExactlyOnce}, and a close while no socket exists takes {@code
 * close()}'s own null-socket path, which this fix does not change.
 */
@Timeout(30)
final class GpgNetConnectionCloseRaceTest {

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

    @Test
    void aCloseInsideTheConnectWindowStillFiresExactlyOneDisconnect() throws Exception {
        AtomicInteger fireCount = new AtomicInteger();
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        // -1 until the seam runs. Records the fire count straight after close() returns: it must be
        // 0, because close() fires only when it finds no socket. Anything else means the seam no
        // longer sits after the socket assignment, and the test would pass without the fix.
        AtomicInteger firedByClose = new AtomicInteger(-1);
        GpgNetConnection racing =
                new GpgNetConnection(server.port(), 5, Duration.ofMillis(20)) {
                    @Override
                    void socketPublished() {
                        close();
                        firedByClose.set(fireCount.get());
                    }
                };
        conn = racing;
        racing.onDisconnect(
                e -> {
                    event.set(e);
                    fireCount.incrementAndGet();
                    disconnected.countDown();
                });

        CompletableFuture<Void> connected = racing.connect();

        assertTrue(
                disconnected.await(5, TimeUnit.SECONDS),
                "a close inside the connect window must still fire the disconnect listener");
        assertEquals(
                0,
                firedByClose.get(),
                "close() must have found the socket already published, or the window was missed");
        assertThrows(
                ExecutionException.class,
                () -> connected.get(5, TimeUnit.SECONDS),
                "the connect was abandoned, so its future must not complete successfully");
        assertEquals(DisconnectReason.LOCAL_CLOSE, event.get().reason());
        assertEquals(1, fireCount.get(), "exactly one disconnect on this path");
    }
}
