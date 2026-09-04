package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectEvent;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.shared.statemachine.Event;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link GameShutdown}: the idempotent close-connection → stop-scheduling sequence.
 * Stopping the logging context is not part of it — the bootstrap owns that step (WBS-3.2.5.1), so
 * nothing here can silence the rest of the suite.
 *
 * <p>The order is load-bearing rather than cosmetic, so it is covered twice: once directly, and
 * once by {@link #completesWhileATransitionActionIsStalledMidWrite()}, which reproduces the stall
 * the order exists to break.
 */
final class GameShutdownTest {

    /**
     * Payload per frame in the stall test. Measured on this project's JDK 21 toolchain: with the
     * server's receive buffer pinned small, four of these fill the pair of kernel buffers and the
     * fifth write blocks.
     */
    private static final int STALL_FRAME_BYTES = 512 * 1024;

    /** Safety cap on the stall loop — 64MB, far past any plausible loopback buffer pair. */
    private static final int STALL_FRAME_CAP = 128;

    private static final String STILL_WRITING = "still-writing";
    private static final String WRITE_FAILED = "write-failed";
    private static final String CAP_REACHED = "cap-reached";

    @Test
    void runsStepsInOrderCloseConnectionThenStopScheduling() {
        // A never-connected GpgNetConnection closes synchronously (its disconnect fires on this
        // thread), so both steps record their order deterministically.
        List<String> order = new CopyOnWriteArrayList<>();
        StateMachine fsm = recordingFsm(order);
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> order.add("close-connection"));

        new GameShutdown(fsm, connection).run();

        assertEquals(
                List.of("close-connection", "stop-scheduling"),
                order,
                "the close must lead: it is what releases a transition action stalled in a write,"
                        + " and stopping the scheduling needs that action's monitor");
    }

    @Test
    void closesLiveGpgNetSocket() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptOneClient(server);

            GpgNetConnection connection =
                    new GpgNetConnection(server.getLocalPort(), 5, Duration.ofMillis(20));
            CountDownLatch disconnected = new CountDownLatch(1);
            AtomicReference<DisconnectEvent> event = new AtomicReference<>();
            connection.onDisconnect(
                    e -> {
                        event.set(e);
                        disconnected.countDown();
                    });
            connection.connect().get(5, TimeUnit.SECONDS);

            new GameShutdown(quietFsm(), connection).run();

            assertTrue(disconnected.await(2, TimeUnit.SECONDS), "the socket should be closed");
            assertEquals(DisconnectReason.LOCAL_CLOSE, event.get().reason());
        }
    }

    @Test
    void stopsFsmTimeoutScheduling() throws Exception {
        State idle = new State("A");
        State ended = new State("B");
        StateMachine fsm = new StateMachine(idle);
        fsm.setTimeout(150, ended);

        new GameShutdown(fsm, null).run();

        Thread.sleep(300); // past the 150ms timeout — it must not fire after shutdown
        assertSame(idle, fsm.getState(), "shutdown must cancel the FSM's scheduled timeout");
    }

    @Test
    void isIdempotentAcrossSecondRun() {
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> closes.incrementAndGet());

        GameShutdown shutdown = new GameShutdown(countingFsm(cancels), connection);
        shutdown.run();
        shutdown.run(); // second call must be a no-op

        assertEquals(1, cancels.get(), "FSM scheduling stopped exactly once");
        assertEquals(1, closes.get(), "connection closed exactly once");
    }

    @Test
    void runsQuietlyWhenGameNeverConnected() {
        AtomicInteger cancels = new AtomicInteger();

        // No connection registered at all.
        new GameShutdown(countingFsm(cancels), null).run();

        assertEquals(1, cancels.get(), "scheduling is still stopped without a connection");
    }

    @Test
    void secondCallerDoesNotBlockWhileTheFirstIsStillTearingDown() throws Exception {
        // The lock-ordering regression this guards: the FSM thread enters run() from the ENDED
        // entry hook while holding the StateMachine monitor, and the JVM shutdown hook calls run()
        // concurrently. If run() took a monitor, the hook thread would hold it and then block in
        // fsm.cancel() waiting for the StateMachine monitor the FSM thread already owns.
        CountDownLatch insideFirstRun = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        StateMachine blockingFsm =
                new StateMachine(new State("A")) {
                    @Override
                    public void cancel() {
                        insideFirstRun.countDown();
                        try {
                            releaseFirstRun.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        super.cancel();
                    }
                };
        GameShutdown shutdown = new GameShutdown(blockingFsm, null);

        Thread first = startDaemon(shutdown, "first-shutdown-caller");
        assertTrue(
                insideFirstRun.await(5, TimeUnit.SECONDS), "the first caller should be in run()");

        CountDownLatch secondReturned = new CountDownLatch(1);
        startDaemon(
                () -> {
                    shutdown.run();
                    secondReturned.countDown();
                },
                "second-shutdown-caller");

        assertTrue(
                secondReturned.await(2, TimeUnit.SECONDS),
                "the second caller must return while the first is still tearing down");
        releaseFirstRun.countDown();
        first.join(5_000);
        assertFalse(first.isAlive(), "the first caller should finish once released");
    }

    @Test
    void closesConnectionRegisteredBeforeRun() {
        AtomicBoolean closed = new AtomicBoolean(false);
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> closed.set(true));

        GameShutdown shutdown = new GameShutdown(quietFsm(), null);
        shutdown.registerConnection(connection);
        shutdown.run();

        assertTrue(closed.get(), "a connection registered before run() is closed");
    }

    @Test
    void connectionRegisteredAfterRunIsNotClosed() {
        AtomicBoolean closed = new AtomicBoolean(false);
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> closed.set(true));

        GameShutdown shutdown = new GameShutdown(quietFsm(), null);
        shutdown.run();
        shutdown.registerConnection(connection); // too late

        assertFalse(closed.get(), "a connection registered after run() is not closed by it");
    }

    /**
     * The regression from #299. A transition action blocked in a socket write holds the
     * StateMachine monitor, so {@link StateMachine#cancel()} cannot run — and under the old
     * stop-scheduling-first order, the close that would release the write sat behind that wait, so
     * teardown never returned and the client SIGKILLed the game after its 5s grace. Not
     * hypothetical: the pinned faf-ice-adapter blocks its own GPGNet read loop on {@code
     * getPeerOrWait} until a JSON-RPC peer attaches, so it accepts this connection and then stops
     * reading it.
     *
     * <p>This does <em>not</em> cover the other precondition of the ordering, that a local close
     * posts nothing into the FSM. The connection here is live, so its disconnect is delivered on
     * the reader thread and could not hold up teardown however it behaved; only the never-connected
     * path dispatches on the caller's thread.
     */
    @Test
    @Timeout(30)
    void completesWhileATransitionActionIsStalledMidWrite() throws Exception {
        try (ServerSocket server = deafServer()) {
            acceptAndNeverRead(server);
            GpgNetConnection connection =
                    new GpgNetConnection(server.getLocalPort(), 20, Duration.ofMillis(20));
            connection.connect().get(5, TimeUnit.SECONDS);

            CountDownLatch actionEntered = new CountDownLatch(1);
            AtomicLong framesSent = new AtomicLong();
            AtomicReference<String> outcome = new AtomicReference<>(STILL_WRITING);
            State stalling = new State("STALLING");
            stalling.registerTransition(
                    StallEvent.class,
                    new State("RELEASED"),
                    ignored -> stallInWrite(connection, actionEntered, framesSent, outcome),
                    null);
            StateMachine fsm = new StateMachine(stalling);

            // receiveEvent is synchronized for its whole body, so once the action reports itself
            // running the FSM thread provably holds the monitor cancel() needs.
            Thread fsmThread =
                    startDaemon(() -> fsm.receiveEvent(new StallEvent()), "stalled-transition");
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS), "the action should be running");
            awaitStalledWrite(framesSent, outcome);

            CountDownLatch teardownReturned = new CountDownLatch(1);
            startDaemon(
                    () -> {
                        new GameShutdown(fsm, connection).run();
                        teardownReturned.countDown();
                    },
                    "stalled-teardown");

            assertTrue(
                    teardownReturned.await(5, TimeUnit.SECONDS),
                    "teardown must not block behind the write its own first step unblocks");
            fsmThread.join(5_000);
            assertFalse(
                    fsmThread.isAlive(), "closing the socket should release the stalled action");
            // The vacuity guard. Had the writes all gone through instead of stalling, the action
            // would have reported CAP_REACHED and awaitStalledWrite would have aborted the test as
            // inapplicable rather than passing it on a window that was never contended.
            assertEquals(
                    WRITE_FAILED,
                    outcome.get(),
                    "the stalled write should have failed, not caught up");
        }
    }

    /**
     * The invariant this order trades away, confirmed rather than assumed. Modelled on the only
     * timeout the mock game actually arms ({@code MockGameLifecycle}'s GPGNet connect timeout): its
     * action writes nothing, it targets ENDED, and ENDED's entry hook is this same once-guarded
     * sequence. Firing it in the window between the close and {@link StateMachine#cancel()} must
     * therefore converge where teardown was already going, without tearing down twice.
     */
    @Test
    @Timeout(30)
    void timeoutFiringBetweenCloseAndCancelIsBenign() throws Exception {
        // Ordered rather than merely counted: the point of the test is that the timeout fires
        // inside the window, so the close has to be observably already done when it does.
        List<String> order = new CopyOnWriteArrayList<>();
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> order.add("close-connection"));

        CountDownLatch timeoutFired = new CountDownLatch(1);
        AtomicInteger entryHookRuns = new AtomicInteger();
        State initializing = new State("INITIALIZING");
        State ended = new State("ENDED");
        // Not synchronized: UpdateStateTask.run takes this machine's monitor, so an override that
        // held it while waiting would block the very timer thread expected to release the latch.
        StateMachine fsm =
                new StateMachine(initializing) {
                    @Override
                    public void cancel() {
                        try {
                            timeoutFired.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        super.cancel();
                    }
                };
        GameShutdown shutdown = new GameShutdown(fsm, connection);
        ended.onEntry(
                () -> {
                    entryHookRuns.incrementAndGet();
                    shutdown.run(); // re-entrant; must no-op on the once-guard
                });
        fsm.setTimeout(
                50,
                ended,
                ignored -> {
                    order.add("timeout-fired");
                    timeoutFired.countDown();
                });

        CountDownLatch teardownReturned = new CountDownLatch(1);
        startDaemon(
                () -> {
                    shutdown.run();
                    teardownReturned.countDown();
                },
                "teardown-with-armed-timeout");

        assertTrue(teardownReturned.await(10, TimeUnit.SECONDS), "teardown must still return");
        assertEquals(
                List.of("close-connection", "timeout-fired"),
                order,
                "the timeout must fire after the close — otherwise this is not the traded window");
        assertSame(ended, fsm.getState(), "a timeout in the window converges on its target state");
        assertEquals(1, entryHookRuns.get(), "the target state was entered once");
    }

    @Test
    void rejectsNullFsm() {
        assertThrows(NullPointerException.class, () -> new GameShutdown(null));
    }

    /** Event with no meaning beyond triggering the stalling transition. */
    private static final class StallEvent implements Event {}

    /**
     * A server that accepts but never reads, with its receive buffer pinned small before bind —
     * which on Linux also disables receive-window autotuning, so the buffer pair the writer has to
     * fill stays small and the stall arrives in milliseconds.
     */
    private static ServerSocket deafServer() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReceiveBufferSize(8192);
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        return server;
    }

    /** Accepts one client and holds it open without ever reading a byte. */
    private static void acceptAndNeverRead(final ServerSocket server) {
        startDaemon(
                () -> {
                    try (Socket held = server.accept()) {
                        while (!server.isClosed() && held.isConnected()) {
                            Thread.sleep(50);
                        }
                    } catch (IOException | InterruptedException ignored) {
                        // Server stopped or the test ended.
                    }
                },
                "deaf-gpgnet-server");
    }

    /** Sends until the socket blocks, reporting how it ended. Runs as a transition action. */
    private static void stallInWrite(
            final GpgNetConnection connection,
            final CountDownLatch entered,
            final AtomicLong framesSent,
            final AtomicReference<String> outcome) {
        String payload = "x".repeat(STALL_FRAME_BYTES);
        entered.countDown();
        try {
            for (int i = 0; i < STALL_FRAME_CAP; i++) {
                connection.send(GpgNetFrame.of("Stall", payload));
                framesSent.incrementAndGet();
            }
            outcome.set(CAP_REACHED);
        } catch (IOException e) {
            outcome.set(WRITE_FAILED);
        }
    }

    /**
     * Waits until the send count stops advancing, which is the only usable signal that the write
     * has blocked: a thread parked in {@code NioSocketImpl}'s write poll reports {@code RUNNABLE},
     * so {@link Thread.State} cannot distinguish it from one that is simply busy.
     *
     * <p>Releasing early can only weaken this test, never redden it — the close then makes the next
     * send throw and every assertion still holds. Releasing late is bounded by {@link
     * #STALL_FRAME_CAP}, and exhausting the cap aborts as an unmet assumption rather than failing,
     * so a host with unusually large TCP buffers reports "not applicable" instead of a red build.
     */
    private static void awaitStalledWrite(
            final AtomicLong framesSent, final AtomicReference<String> outcome)
            throws InterruptedException {
        long previous = -1;
        int stableChecks = 0;
        while (stableChecks < 3) {
            Thread.sleep(100);
            if (CAP_REACHED.equals(outcome.get())) {
                abort(
                        "could not fill the socket buffers within "
                                + STALL_FRAME_CAP
                                + " frames; this host's TCP buffers exceed what the test assumes");
            }
            long sent = framesSent.get();
            stableChecks = sent == previous ? stableChecks + 1 : 0;
            previous = sent;
        }
    }

    /** A StateMachine that records "stop-scheduling" when its scheduling is cancelled. */
    private static StateMachine recordingFsm(final List<String> order) {
        return new StateMachine(new State("A")) {
            @Override
            public void cancel() {
                order.add("stop-scheduling");
                super.cancel();
            }
        };
    }

    /** A plain FSM whose cancellation is a harmless no-op observation. */
    private static StateMachine quietFsm() {
        return new StateMachine(new State("A"));
    }

    /** An FSM that counts how many times its scheduling was cancelled. */
    private static StateMachine countingFsm(final AtomicInteger cancels) {
        return new StateMachine(new State("A")) {
            @Override
            public void cancel() {
                cancels.incrementAndGet();
                super.cancel();
            }
        };
    }

    /** Starts {@code body} on a named daemon thread. */
    private static Thread startDaemon(final Runnable body, final String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Accepts a single client on {@code server} in the background, then holds the socket open. */
    private static void acceptOneClient(final ServerSocket server) {
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                Socket client = server.accept();
                                // Hold the connection until the test closes the server.
                                while (!server.isClosed() && client.isConnected()) {
                                    if (client.getInputStream().read() < 0) {
                                        break;
                                    }
                                }
                            } catch (IOException ignored) {
                                // server stopped or client gone
                            }
                        },
                        "gameshutdown-test-accept");
        thread.setDaemon(true);
        thread.start();
    }
}
