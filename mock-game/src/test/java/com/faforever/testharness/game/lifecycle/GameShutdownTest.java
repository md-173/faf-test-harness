package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectEvent;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import com.faforever.testharness.game.net.GameTrafficSession;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.io.IOException;
import java.net.DatagramSocket;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameShutdown}: the idempotent stop-scheduling → close-connection →
 * close-traffic sequence. Stopping the logging context is not part of it — the bootstrap owns that
 * step (WBS-3.2.5.1), so nothing here can silence the rest of the suite.
 */
final class GameShutdownTest {

    @Test
    void runsStepsInOrderStopSchedulingThenCloseConnectionThenTraffic() throws Exception {
        // A never-connected GpgNetConnection closes synchronously (its disconnect fires on this
        // thread), so every step records its order deterministically.
        List<String> order = new CopyOnWriteArrayList<>();
        StateMachine fsm = recordingFsm(order);
        GameTrafficSession traffic = boundTrafficSession();
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(
                event -> {
                    order.add("close-connection");
                    // Sampled here rather than after run() returns: read afterwards, this would
                    // say "closed" no matter which step ran first, and the ordering claim below
                    // would be unearned.
                    order.add(traffic.isClosed() ? "traffic-already-closed" : "traffic-still-open");
                });

        GameShutdown shutdown = new GameShutdown(fsm, connection);
        shutdown.registerTrafficSession(traffic);
        shutdown.run();
        order.add(traffic.isClosed() ? "traffic-closed" : "traffic-never-closed");

        assertEquals(
                List.of(
                        "stop-scheduling",
                        "close-connection",
                        "traffic-still-open",
                        "traffic-closed"),
                order,
                "scheduling must stop first so no timeout fires mid-teardown, and the traffic "
                        + "session must still be open while the connection closes");
    }

    @Test
    void closesTheTrafficSessionRegisteredBeforeRun() throws Exception {
        GameTrafficSession traffic = boundTrafficSession();

        GameShutdown shutdown = new GameShutdown(quietFsm(), null);
        shutdown.registerTrafficSession(traffic);
        shutdown.run();

        assertTrue(
                traffic.isClosed(),
                "the sequence must close the traffic session it was given; that closing it really "
                        + "releases the socket is LifecycleTrafficWiringTest's assertion");
    }

    @Test
    void trafficSessionRegisteredAfterRunIsNotClosed() throws Exception {
        GameTrafficSession traffic = boundTrafficSession();

        GameShutdown shutdown = new GameShutdown(quietFsm(), null);
        shutdown.run();
        shutdown.registerTrafficSession(traffic); // too late

        assertFalse(traffic.isClosed(), "a session registered after run() is not closed by it");
        traffic.close();
    }

    @Test
    void rejectsNullTrafficSession() {
        GameShutdown shutdown = new GameShutdown(quietFsm(), null);

        assertThrows(NullPointerException.class, () -> shutdown.registerTrafficSession(null));
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

    @Test
    void rejectsNullFsm() {
        assertThrows(NullPointerException.class, () -> new GameShutdown(null));
    }

    /**
     * A traffic session with its lobby socket bound on a free port. Nothing is sent: the cadence
     * starts on the first registered peer, and this sequence's only interest is the teardown.
     */
    private static GameTrafficSession boundTrafficSession() throws IOException {
        final int lobbyPort;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            lobbyPort = probe.getLocalPort();
        }
        GameTrafficSession traffic = new GameTrafficSession(1);
        traffic.bind(lobbyPort);
        return traffic;
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
