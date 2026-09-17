package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectEvent;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectReason;
import java.io.IOException;
import java.time.Duration;
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
import org.junit.jupiter.api.Timeout;

/**
 * {@link IceAdapterConnection#close()} racing the connect (WBS-3.1.4.1-fix, #278).
 *
 * <p>The connection fires its disconnect listener exactly once — on connect failure, on remote
 * close or read error, or on {@code close()}. There was one window where it fired <em>not at
 * all</em>: {@code runConnection} publishes the socket and only then reads the close flag, and
 * {@code close()} fires {@code LOCAL_CLOSE} itself only on the path where it finds a null socket. A
 * {@code close()} landing between those two instructions therefore took the "close the live socket"
 * path and left the event to the connect thread, which returned having fired nothing.
 *
 * <p>Impact was nil by coincidence rather than design: {@code connectionOpened} is set after the
 * branch, so {@code isOpen()} answered {@code false} either way and {@code SessionTeardown} skipped
 * its quit-first step correctly. Move that assignment earlier, or add any consumer reading {@code
 * disconnectFired} directly, and a lost event becomes {@code isOpen()} reporting a dead connection
 * as open.
 *
 * <p>The window is two instructions wide and has no observable edge, so the middle case here drives
 * it through the {@code socketPublished()} seam rather than by racing threads and hoping. The two
 * cases either side of it need no seam and are here to pin that the fix did not disturb them.
 *
 * <p><b>The connect thread reporting first while no socket exists</b> (WBS-3.2.2.1-fix, #404).
 * {@code close()} fires {@code LOCAL_CLOSE} itself there and usually first, so the {@code
 * closeFlagSet()} seam holds it after it sets the flag and short of its own fire. The connect
 * thread's report must then be {@code LOCAL_CLOSE} too, whether the close stopped the retrying or
 * landed after the last in-loop check; the second used to report {@code CONNECT_FAILED} and log a
 * WARN claiming the adapter was never reachable. The last case goes the other way: a caller that
 * closes because the connect failed, as {@code LaunchIceCommand} does, must leave a genuine failure
 * reported and logged as one, because the failure catch logs and fires before it fails the future.
 * These mirror mock-game's {@code GpgNetConnectionCloseRaceTest}.
 */
@Timeout(30)
final class IceAdapterConnectionCloseRaceTest {

    /**
     * A port nothing can be listening on, below every platform's ephemeral range, so no {@code
     * bind(0)} in this JVM can be handed it, as in {@code IceAdapterConnectionTest}. An environment
     * that drops the connect rather than refusing it costs at most the 1 s connect timeout per
     * attempt.
     */
    private static final int UNBOUND_PORT = 1;

    /** Short, since every case here either connects to a live fixture or is closed at once. */
    private static final int ATTEMPTS = 20;

    private static final Duration RETRY_DELAY = Duration.ofMillis(20);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(1);

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

    /**
     * The defect. {@code close()} is invoked from inside the window itself, so the socket is
     * already published and the flag is not yet read — exactly the interleaving that fired nothing.
     */
    @Test
    void aCloseInsideTheConnectWindowStillFiresExactlyOneDisconnect() throws Exception {
        List<DisconnectEvent> fired = new CopyOnWriteArrayList<>();
        IceAdapterConnection racing =
                new IceAdapterConnection(server.port(), ATTEMPTS, RETRY_DELAY, CALL_TIMEOUT) {
                    @Override
                    void socketPublished() {
                        // On the connect thread, between the socket assignment and the flag read.
                        close();
                    }
                };
        conn = racing;
        racing.onDisconnect(fired::add);

        CompletableFuture<Void> connected = racing.connect();

        assertThrows(
                ExecutionException.class,
                () -> connected.get(5, TimeUnit.SECONDS),
                "the connect was abandoned, so its future must not complete successfully");
        // Awaited, not read straight after the future. runConnection calls completeExceptionally
        // before fireDisconnect, so connected.get() can return while fired is still empty —
        // measured at roughly 1 failure in 100 on an idle machine, and every time with a delay
        // injected between the two production lines. A CI flake there would read "must fire
        // exactly one disconnect, not none: []", which looks exactly like the fix regressing.
        assertTrue(
                waitFor(() -> !fired.isEmpty()),
                "a close in this window must fire a disconnect, not none");
        assertEquals(
                1,
                fired.size(),
                "a close in this window must fire exactly one disconnect: " + fired);
        assertEquals(DisconnectReason.LOCAL_CLOSE, fired.get(0).reason());
        assertFalse(racing.isOpen(), "a closed connection must never report itself open");
    }

    /**
     * A pending {@code call} made in the same window is failed rather than left hanging. {@code
     * out} is assigned before the branch, so a concurrent caller can already have registered a
     * future that nothing else would ever complete.
     */
    @Test
    void aCallOutstandingAcrossTheWindowIsFailedRatherThanLeftPending() throws Exception {
        // Handed out from the connect thread, so the assertion below waits for the call to have
        // been registered rather than racing it.
        CompletableFuture<CompletableFuture<?>> registered = new CompletableFuture<>();
        IceAdapterConnection racing =
                new IceAdapterConnection(server.port(), ATTEMPTS, RETRY_DELAY, CALL_TIMEOUT) {
                    @Override
                    void socketPublished() {
                        registered.complete(call("iceServers"));
                        close();
                    }
                };
        conn = racing;

        racing.connect();

        CompletableFuture<?> pending = registered.get(5, TimeUnit.SECONDS);
        ExecutionException thrown =
                assertThrows(
                        ExecutionException.class,
                        () -> pending.get(5, TimeUnit.SECONDS),
                        "a call registered before the close must be failed, not left pending");
        // The discriminator. Without failAllPending the future still completes — the call's own
        // orTimeout eventually fires — so asserting only that it failed proves nothing. What the
        // close is supposed to produce is the disconnect, immediately, not a timeout a second
        // later.
        assertEquals(
                IOException.class,
                thrown.getCause().getClass(),
                "the call must be failed by the disconnect, not by its own call timeout: "
                        + thrown.getCause());
        assertTrue(
                thrown.getCause().getMessage().contains(DisconnectReason.LOCAL_CLOSE.toString()),
                "the failure must name the close that caused it: " + thrown.getCause());
    }

    /**
     * The window's near side, which was always correct: {@code close()} before the socket exists
     * finds a null one and fires {@code LOCAL_CLOSE} itself, and the connect thread then returns
     * silently because the event has already gone out.
     */
    @Test
    void aCloseBeforeTheSocketExistsFiresLocalCloseExactlyOnce() throws Exception {
        List<DisconnectEvent> fired = new CopyOnWriteArrayList<>();
        // A port with no listener, so the connect is still retrying when close lands. Not the
        // fixture's own port freed for the purpose: a released port can be handed to the next
        // bind(0) in this JVM, which is what UNBOUND_PORT exists to rule out.
        conn =
                new IceAdapterConnection(
                        UNBOUND_PORT, ATTEMPTS, Duration.ofMillis(100), CALL_TIMEOUT);
        conn.onDisconnect(fired::add);

        CompletableFuture<Void> connected = conn.connect();
        conn.close();

        assertThrows(ExecutionException.class, () -> connected.get(5, TimeUnit.SECONDS));
        // close() fires on this thread only if it wins disconnectFired's CAS; if the connect
        // thread wins via the ConnectAbandonedException path, the winner may still be between the
        // CAS and the listener call when we get here.
        assertTrue(waitFor(() -> !fired.isEmpty()), "a disconnect must fire, whoever fires it");
        assertEquals(1, fired.size(), "exactly one disconnect, whoever fired it: " + fired);
        assertEquals(DisconnectReason.LOCAL_CLOSE, fired.get(0).reason());
    }

    /**
     * The window's far side: a close after the connection is live is delivered by {@code
     * readLoop}'s finally, which reads the same flag to tell a local close from a remote one.
     */
    @Test
    void aCloseAfterTheConnectionIsLiveFiresLocalCloseExactlyOnce() throws Exception {
        List<DisconnectEvent> fired = new CopyOnWriteArrayList<>();
        conn = new IceAdapterConnection(server.port(), ATTEMPTS, RETRY_DELAY, CALL_TIMEOUT);
        conn.onDisconnect(fired::add);
        conn.connect().get(5, TimeUnit.SECONDS);
        assertTrue(conn.isOpen(), "the fixture is live, so this should have connected");

        conn.close();

        assertTrue(
                waitFor(() -> !fired.isEmpty()),
                "the read loop must report the local close it was ended by");
        assertEquals(1, fired.size(), "still exactly once: " + fired);
        assertEquals(DisconnectReason.LOCAL_CLOSE, fired.get(0).reason());
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
        IceAdapterConnection racing =
                new IceAdapterConnection(UNBOUND_PORT, 200, Duration.ofMillis(100), CALL_TIMEOUT) {
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
        IceAdapterConnection racing =
                new IceAdapterConnection(UNBOUND_PORT, 1, Duration.ofMillis(20), CALL_TIMEOUT) {
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

        try (LogCapture log = new LogCapture(IceAdapterConnection.class)) {
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
     * A caller that closes because the connect failed, as {@code LaunchIceCommand} and {@code
     * IceReachabilityCheck} do, must not relabel a genuine failure. The failure is logged at WARN
     * and reported as {@code CONNECT_FAILED} before the future fails, so a close made in reaction
     * comes too late to change either. {@code connectFailed()} holds the connect thread until the
     * reaction is registered, so the reaction runs as the future fails. Two attempts rather than
     * one keep this failure's message distinct from the case above.
     */
    @Test
    void aCloseMadeBecauseTheConnectFailedLeavesItAConnectFailure() throws Exception {
        CountDownLatch inFailureCatch = new CountDownLatch(1);
        CountDownLatch reactionRegistered = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<DisconnectEvent> event = new AtomicReference<>();
        IceAdapterConnection failing =
                new IceAdapterConnection(UNBOUND_PORT, 2, Duration.ofMillis(20), CALL_TIMEOUT) {
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

        try (LogCapture log = new LogCapture(IceAdapterConnection.class)) {
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

    /** Polls {@code condition} for up to five seconds. */
    private static boolean waitFor(final java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
