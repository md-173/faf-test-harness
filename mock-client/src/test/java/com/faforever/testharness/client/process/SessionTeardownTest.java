package com.faforever.testharness.client.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** Adapter-connection stub: never connects, runs the given action when closed. */
    private static final class RecordingAdapterConnection extends IceAdapterConnection {
        private final Runnable onClose;

        RecordingAdapterConnection(final Runnable onClose) {
            super(1);
            this.onClose = onClose;
        }

        @Override
        public void close() {
            onClose.run();
        }
    }
}
