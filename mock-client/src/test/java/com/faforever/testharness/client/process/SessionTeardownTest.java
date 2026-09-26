package com.faforever.testharness.client.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionTeardown}. The subprocess handles are real {@link
 * SubprocessManager}s wrapping {@code sleep} children — {@code SubprocessManager} is final, and
 * observing real processes die is exactly the "no orphans" acceptance signal. The lobby handle is a
 * real (never-connected) {@link LobbyConnection} whose close is observed via its disconnect
 * listener; only the adapter connection is a recording stub.
 */
final class SessionTeardownTest {

    /** Teardown steps in observed order. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    /** Sleeper children started by a test; terminated in {@link #tearDown()} as a safety net. */
    private final List<SubprocessManager> sleepers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (SubprocessManager sleeper : sleepers) {
            sleeper.terminate();
        }
    }

    /** Starts a long-sleeping child the test expects {@link SessionTeardown} to kill. */
    private SubprocessManager startSleeper() throws Exception {
        SubprocessManager sleeper =
                SubprocessManager.start(
                        new ProcessBuilder("sleep", "60"),
                        "teardown-test-child",
                        Duration.ofSeconds(2));
        sleepers.add(sleeper);
        return sleeper;
    }

    /** A real lobby connection (never connected) whose close is recorded via onDisconnect. */
    private LobbyConnection recordingLobby() {
        LobbyConnection lobby = new LobbyConnection(URI.create("ws://127.0.0.1:1"));
        lobby.onDisconnect(event -> events.add("lobby-closed"));
        return lobby;
    }

    /** One call kills both processes, then closes the connections, adapter RPC before lobby. */
    @Test
    void runTerminatesProcessesThenClosesConnections() throws Exception {
        SubprocessManager game = startSleeper();
        SubprocessManager adapter = startSleeper();

        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerGameProcess(game);
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(
                new RecordingAdapterConnection(
                        () ->
                                events.add(
                                        game.isAlive() || adapter.isAlive()
                                                ? "rpc-closed-before-processes-died"
                                                : "rpc-closed-after-processes")));

        teardown.run();

        assertFalse(game.isAlive(), "game process must be terminated — the no-orphans signal");
        assertFalse(adapter.isAlive(), "adapter process must be terminated");
        assertEquals(
                List.of("rpc-closed-after-processes", "lobby-closed"),
                events,
                "documented order: processes first, then adapter RPC, then lobby");
    }

    /** A second run() call is a no-op — no step executes twice. */
    @Test
    void secondRunIsNoOp() {
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));

        teardown.run();
        teardown.run();

        assertEquals(List.of("rpc-closed", "lobby-closed"), events);
    }

    /**
     * Concurrent run() calls execute the sequence exactly once — the losing thread blocks on the
     * monitor, then no-ops. The monitor serializes the threads, so a broken guard would be caught
     * probabilistically; the test's main job is pinning the documented concurrent-no-op contract.
     */
    @Test
    void concurrentRunsExecuteTeardownOnce() throws Exception {
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));
        teardown.registerAfterGameStep(() -> events.add("step"));

        CountDownLatch go = new CountDownLatch(1);
        Runnable call =
                () -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    teardown.run();
                };
        Thread first = new Thread(call);
        Thread second = new Thread(call);
        first.start();
        second.start();
        go.countDown();
        first.join();
        second.join();

        assertEquals(
                List.of("step", "rpc-closed", "lobby-closed"),
                events,
                "each teardown step, the owner's included, must fire exactly once across concurrent"
                        + " callers");
    }

    /**
     * The owner's step runs once the game is down and before the adapter is touched (#454): where
     * the lifecycle reports the game's end, with the lobby still open.
     */
    @Test
    void theAfterGameStepRunsBetweenTheGameAndTheAdapter() throws Exception {
        SubprocessManager game = startSleeper();
        SubprocessManager adapter = startSleeper();
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerGameProcess(game);
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));
        teardown.registerAfterGameStep(
                () ->
                        events.add(
                                "step: game "
                                        + (game.isAlive() ? "alive" : "down")
                                        + ", adapter "
                                        + (adapter.isAlive() ? "alive" : "down")));

        teardown.run();

        assertEquals(
                List.of("step: game down, adapter alive", "rpc-closed", "lobby-closed"), events);
    }

    /** A step that throws is the owner's defect, logged, and the rest of teardown still runs. */
    @Test
    void aThrowingAfterGameStepStillTearsDownTheRest() throws Exception {
        SubprocessManager adapter = startSleeper();
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));
        teardown.registerAfterGameStep(
                () -> {
                    throw new IllegalStateException("defect in the owner's step");
                });

        teardown.run();

        assertFalse(adapter.isAlive(), "the adapter must still be terminated");
        assertEquals(List.of("rpc-closed", "lobby-closed"), events);
    }

    /** An idle, lobby-only session tears down cleanly — unregistered handles are skipped. */
    @Test
    void runToleratesUnregisteredHandles() {
        new SessionTeardown(recordingLobby()).run();

        assertEquals(List.of("lobby-closed"), events);
    }

    /** A step that throws is logged and skipped; the rest of the sequence still runs. */
    @Test
    void failingAdapterCloseStillClosesLobby() {
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterRpc(
                new RecordingAdapterConnection(
                        () -> {
                            throw new RuntimeException("adapter socket already broken");
                        }));

        teardown.run();

        assertEquals(List.of("lobby-closed"), events);
    }

    /**
     * A never-connected adapter RPC connection (as every existing test above uses) reports {@link
     * IceAdapterConnection#isOpen()} {@code false} — {@link SessionTeardown} must skip the quit RPC
     * and terminate the adapter the same way it always has.
     */
    @Test
    void rpcNeverOpenedSkipsQuitAndTerminatesAsBefore() throws Exception {
        SubprocessManager adapter = startSleeper();
        RecordingAdapterConnection rpc =
                new RecordingAdapterConnection(() -> events.add("rpc-closed"));

        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(rpc);

        assertFalse(rpc.isOpen(), "a never-connected connection must report closed");
        teardown.run();

        assertFalse(rpc.quitCalled, "quit must not be sent over an RPC connection that isn't open");
        assertFalse(adapter.isAlive(), "adapter must still be terminated via SIGTERM/SIGKILL");
    }

    /**
     * {@link SessionTeardown#signalled()} reads the supplier it was built with (#438), and a
     * teardown built without one never reports a signal.
     */
    @Test
    void signalledFollowsItsSupplier() {
        AtomicBoolean flag = new AtomicBoolean();
        SessionTeardown teardown = new SessionTeardown(recordingLobby(), flag::get);

        assertFalse(teardown.signalled());
        flag.set(true);
        assertTrue(teardown.signalled());
        assertFalse(new SessionTeardown(recordingLobby()).signalled(), "no supplier, no signal");
    }

    /** Adapter-connection stub: never connects, runs the given action when closed. */
    private static final class RecordingAdapterConnection extends IceAdapterConnection {
        private final Runnable onClose;
        private volatile boolean quitCalled;

        RecordingAdapterConnection(final Runnable onClose) {
            super(1);
            this.onClose = onClose;
        }

        @Override
        public CompletableFuture<JsonNode> call(final String method, final Object... params) {
            if ("quit".equals(method)) {
                quitCalled = true;
            }
            return super.call(method, params);
        }

        @Override
        public void close() {
            onClose.run();
        }
    }
}
