package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectEvent;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameShutdown}: the idempotent stop-scheduling → close-connection →
 * flush-logging sequence. A recording log-flush is injected so the real logging context is never
 * torn down mid-suite.
 */
final class GameShutdownTest {

    @Test
    void runsStepsInOrderStopSchedulingCloseConnectionFlushLogging() {
        // A never-connected GpgNetConnection closes synchronously (its disconnect fires on this
        // thread), so all three steps record their order deterministically.
        List<String> order = new CopyOnWriteArrayList<>();
        StateMachine fsm = recordingFsm(order);
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> order.add("close-connection"));

        GameShutdown shutdown = new GameShutdown(fsm, connection, () -> order.add("flush-logging"));
        shutdown.run();

        assertEquals(
                List.of("stop-scheduling", "close-connection", "flush-logging"),
                order,
                "logging must be last so the earlier steps are still logged");
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

            new GameShutdown(quietFsm(), connection, () -> {}).run();

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

        new GameShutdown(fsm, null, () -> {}).run();

        Thread.sleep(300); // past the 150ms timeout — it must not fire after shutdown
        assertSame(idle, fsm.getState(), "shutdown must cancel the FSM's scheduled timeout");
    }

    @Test
    void isIdempotentAcrossSecondRun() {
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        StateMachine fsm =
                new StateMachine(new State("A")) {
                    @Override
                    public void cancel() {
                        cancels.incrementAndGet();
                        super.cancel();
                    }
                };
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> closes.incrementAndGet());

        GameShutdown shutdown = new GameShutdown(fsm, connection, flushes::incrementAndGet);
        shutdown.run();
        shutdown.run(); // second call must be a no-op

        assertEquals(1, cancels.get(), "FSM scheduling stopped exactly once");
        assertEquals(1, closes.get(), "connection closed exactly once");
        assertEquals(1, flushes.get(), "logging flushed exactly once");
    }

    @Test
    void runsQuietlyWhenGameNeverConnected() {
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        StateMachine fsm =
                new StateMachine(new State("A")) {
                    @Override
                    public void cancel() {
                        cancels.incrementAndGet();
                        super.cancel();
                    }
                };

        // No connection registered at all.
        new GameShutdown(fsm, null, flushes::incrementAndGet).run();

        assertEquals(1, cancels.get(), "scheduling is still stopped without a connection");
        assertEquals(1, flushes.get(), "logging is still flushed without a connection");
    }

    @Test
    void closesConnectionRegisteredBeforeRun() {
        AtomicBoolean closed = new AtomicBoolean(false);
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> closed.set(true));

        GameShutdown shutdown = new GameShutdown(quietFsm(), null, () -> {});
        shutdown.registerConnection(connection);
        shutdown.run();

        assertTrue(closed.get(), "a connection registered before run() is closed");
    }

    @Test
    void connectionRegisteredAfterRunIsNotClosed() {
        AtomicBoolean closed = new AtomicBoolean(false);
        GpgNetConnection connection = new GpgNetConnection(1);
        connection.onDisconnect(event -> closed.set(true));

        GameShutdown shutdown = new GameShutdown(quietFsm(), null, () -> {});
        shutdown.run();
        shutdown.registerConnection(connection); // too late

        assertFalse(closed.get(), "a connection registered after run() is not closed by it");
    }

    @Test
    void rejectsNullFsm() {
        assertThrows(NullPointerException.class, () -> new GameShutdown(null));
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
