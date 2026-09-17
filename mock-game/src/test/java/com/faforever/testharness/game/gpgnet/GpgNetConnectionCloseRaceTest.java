package com.faforever.testharness.game.gpgnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
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
 * reported {@code CONNECT_FAILED}, which the lifecycle posts to its FSM in the middle of teardown,
 * and logged a WARN claiming the adapter was never reachable (#404).
 *
 * <p><b>A close made because the connect failed.</b> The failure catch logs and reports before it
 * fails the future, so a caller that closes in reaction, as mock-client's {@code LaunchIceCommand}
 * does, cannot turn a genuine failure into a quiet local close. The {@code connectFailed()} seam
 * holds the connect thread until that reaction is registered.
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
     * by the connect thread as {@code LOCAL_CLOSE}, not {@code CONNECT_FAILED}, and logged at DEBUG
     * rather than as an unreachable adapter. {@code connectFailed()} signals that the connect
     * thread is in its failure catch, past the one in-loop check a single attempt makes, so the
     * close cannot stop the retrying instead; it then holds that thread until the flag is set.
     */
    @Test
    void aCloseRacingTheFinalFailedAttemptIsReportedAsAQuietLocalClose() throws Exception {
        Thread closer = Thread.currentThread();
        CountDownLatch inFailureCatch = new CountDownLatch(1);
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
                        inFailureCatch.countDown();
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

        try (LogCapture log = new LogCapture(GpgNetConnection.class)) {
            CompletableFuture<Void> connected = racing.connect();
            assertTrue(inFailureCatch.await(5, TimeUnit.SECONDS), "the single attempt must fail");
            racing.close();

            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the listener must fire");
            assertThrows(ExecutionException.class, () -> connected.get(5, TimeUnit.SECONDS));
            assertNotSame(closer, firedOn.get(), "the connect thread must report, not close()");
            assertNotNull(
                    event.get().error(),
                    "the report must come from the failure catch, which carries the connect error");
            assertEquals(
                    DisconnectReason.LOCAL_CLOSE,
                    event.get().reason(),
                    "a close requested before the failure was reported is a local close");
            String failure = event.get().error().getMessage();
            assertFalse(
                    log.contains(Level.WARN, failure),
                    "our own close is not an unreachable adapter: " + log.events());
            assertTrue(
                    log.contains(Level.DEBUG, "close landed in the last attempt (" + failure + ")"),
                    "the close should be logged at DEBUG instead: " + log.events());
        }
    }

    /**
     * A caller that closes because the connect failed must not relabel a genuine failure. The
     * failure is logged at WARN and reported as {@code CONNECT_FAILED} before the future fails, so
     * a close made in reaction comes too late to change either. {@code connectFailed()} holds the
     * connect thread until the reaction is registered, so the reaction runs as the future fails.
     * Two attempts rather than one keep this failure's message distinct from the case above.
     */
    @Test
    void aCloseMadeBecauseTheConnectFailedLeavesItAConnectFailure() throws Exception {
        CountDownLatch inFailureCatch = new CountDownLatch(1);
        CountDownLatch reactionRegistered = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        GpgNetConnection failing =
                new GpgNetConnection(UNBOUND_PORT, 2, Duration.ofMillis(20)) {
                    @Override
                    void connectFailed() {
                        inFailureCatch.countDown();
                        awaitQuietly(reactionRegistered);
                    }
                };
        conn = failing;
        failing.onDisconnect(
                e -> {
                    event.set(e);
                    disconnected.countDown();
                });

        try (LogCapture log = new LogCapture(GpgNetConnection.class)) {
            CompletableFuture<Void> connected = failing.connect();
            assertTrue(inFailureCatch.await(5, TimeUnit.SECONDS), "both attempts must fail");
            CompletableFuture<Void> reaction =
                    connected.whenComplete((ignored, error) -> failing.close());
            reactionRegistered.countDown();

            // The reaction's own future completes only once close() has run.
            assertThrows(ExecutionException.class, () -> reaction.get(5, TimeUnit.SECONDS));
            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the listener must fire");
            assertEquals(
                    DisconnectReason.CONNECT_FAILED,
                    event.get().reason(),
                    "a close made after the failure must not relabel it");
            assertTrue(
                    log.contains(Level.WARN, event.get().error().getMessage()),
                    "a genuine failure must still be logged at WARN: " + log.events());
        }
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
