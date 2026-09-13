package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectEvent;
import com.faforever.testharness.client.ice.IceAdapterConnection.DisconnectReason;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
 */
@Timeout(30)
final class IceAdapterConnectionCloseRaceTest {

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
        assertEquals(
                1,
                fired.size(),
                "a close in this window must fire exactly one disconnect, not none: " + fired);
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
        // A port with no listener, so the connect is still retrying when close lands. Port 1 is
        // below every platform's ephemeral range, so no bind(0) in this JVM can be handed it.
        conn = new IceAdapterConnection(1, ATTEMPTS, Duration.ofMillis(100), CALL_TIMEOUT);
        conn.onDisconnect(fired::add);

        CompletableFuture<Void> connected = conn.connect();
        conn.close();

        assertThrows(ExecutionException.class, () -> connected.get(5, TimeUnit.SECONDS));
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
