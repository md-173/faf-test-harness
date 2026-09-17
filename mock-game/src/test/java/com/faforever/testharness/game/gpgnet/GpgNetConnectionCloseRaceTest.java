package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 * {@link GpgNetConnection#close()} racing the connect (WBS-3.2.2.1-fix, #330). Each case drives one
 * interleaving through the class's package-private seams instead of racing threads and hoping.
 *
 * <p><b>A close inside the connect window.</b> {@code runConnection} publishes the socket and only
 * then reads the close flag, while {@code close()} sets the flag and fires {@code LOCAL_CLOSE}
 * itself only when it finds no socket. A close between those two steps therefore saw a socket, left
 * the event to the connect thread, and that thread returned before the read loop without firing
 * anything: the listener never ran. The {@code socketPublished()} seam calls {@code close()} on the
 * connect thread exactly there.
 *
 * <p>That case waits on the listener, not the connect future, because the future is failed before
 * the disconnect fires. Its exactly-once assertion confirms a single fire on this path; it does not
 * test the one-shot guard, since nothing else can fire here. A close on a live connection is not
 * repeated here: it is {@code GpgNetConnectionTest}'s {@code
 * closeFiresLocalCloseDisconnectExactlyOnce}.
 *
 * <p><b>A close while no socket exists.</b> {@code close()} fires {@code LOCAL_CLOSE} itself, and
 * usually first, so an unheld close says nothing about what the connect thread would have reported.
 * The {@code closeFlagSet()} seam holds the close after it sets the flag and short of its own fire,
 * so the connect thread reports first. Its report must be {@code LOCAL_CLOSE} too, whether the
 * close stopped the retrying or landed after the last in-loop check; before this fix the second
 * reported {@code CONNECT_FAILED}, which the lifecycle posts to its FSM in the middle of teardown.
 */
@Timeout(30)
final class GpgNetConnectionCloseRaceTest {

    /**
     * A port nothing can be listening on, below every platform's ephemeral range, as in {@code
     * GpgNetConnectionTest}.
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

    /**
     * A close that stops the retrying is reported by the connect thread as {@code LOCAL_CLOSE}. The
     * 20 s budget (200 x 100 ms) leaves the retrying under way when the close lands, so its next
     * in-loop check abandons it; the close is held in the seam until that report is out.
     */
    @Test
    void aCloseThatStopsTheRetryingIsReportedByTheConnectThreadAsLocalClose() throws Exception {
        Thread closer = Thread.currentThread();
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        AtomicReference<Thread> firedOn = new AtomicReference<>();
        GpgNetConnection racing =
                new GpgNetConnection(UNBOUND_PORT, 200, Duration.ofMillis(100)) {
                    @Override
                    void closeFlagSet() {
                        // On the closing thread: hold close() short of its own fire.
                        awaitQuietly(disconnected);
                    }
                };
        conn = racing;
        racing.onDisconnect(
                e -> {
                    event.set(e);
                    firedOn.set(Thread.currentThread());
                    disconnected.countDown();
                });

        CompletableFuture<Void> connected = racing.connect();
        racing.close();

        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the disconnect listener must fire");
        assertNotSame(closer, firedOn.get(), "the connect thread must have reported, not close()");
        assertEquals(
                DisconnectReason.LOCAL_CLOSE,
                event.get().reason(),
                "a close that stopped the retrying is a local close");
        assertThrows(ExecutionException.class, () -> connected.get(5, TimeUnit.SECONDS));
    }

    /**
     * A close that lands after the last in-loop check, while the final attempt fails, is reported
     * by the connect thread as {@code LOCAL_CLOSE}, not {@code CONNECT_FAILED}. The failed future
     * shows that thread is already in its failure catch, past the one in-loop check a single
     * attempt makes, so the close cannot stop the retrying instead; {@code connectFailed()} then
     * holds it until the flag is set.
     */
    @Test
    void aCloseRacingTheFinalFailedAttemptIsReportedAsLocalClose() throws Exception {
        Thread closer = Thread.currentThread();
        CountDownLatch flagSet = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        AtomicReference<Thread> firedOn = new AtomicReference<>();
        GpgNetConnection racing =
                new GpgNetConnection(UNBOUND_PORT, 1, Duration.ofMillis(20)) {
                    @Override
                    void closeFlagSet() {
                        // On the closing thread: the flag is set, and close() is held short of its
                        // own fire until the connect thread has fired.
                        flagSet.countDown();
                        awaitQuietly(disconnected);
                    }

                    @Override
                    void connectFailed() {
                        // On the connect thread, before it reads the flag.
                        awaitQuietly(flagSet);
                    }
                };
        conn = racing;
        racing.onDisconnect(
                e -> {
                    event.set(e);
                    firedOn.set(Thread.currentThread());
                    disconnected.countDown();
                });

        CompletableFuture<Void> connected = racing.connect();
        assertThrows(ExecutionException.class, () -> connected.get(5, TimeUnit.SECONDS));
        racing.close();

        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the disconnect listener must fire");
        assertNotSame(closer, firedOn.get(), "the connect thread must have reported, not close()");
        assertNotNull(
                event.get().error(),
                "the report must come from the failure catch, which carries the connect error");
        assertEquals(
                DisconnectReason.LOCAL_CLOSE,
                event.get().reason(),
                "a close requested before the failure was reported is a local close");
    }

    /**
     * Waits up to five seconds. A timeout is not reported here: it lets the held side go on, and
     * the test's own assertions then say which side fired.
     */
    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
