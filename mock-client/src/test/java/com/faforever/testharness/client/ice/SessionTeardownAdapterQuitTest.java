package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers {@link SessionTeardown}'s quit-first adapter termination (WBS-3.1.2.5): while the RPC
 * connection is open, teardown sends {@code quit} and waits briefly for the adapter to exit before
 * falling through to its existing SIGTERM→SIGKILL path. Lives in this package (rather than
 * alongside the rest of {@link SessionTeardown}'s tests) because it needs package-private access to
 * {@link ScriptedJsonRpcServer}, the real socket peer {@link IceAdapterConnection} speaks to.
 *
 * <p>The adapter process itself is a real {@code sleep} child (the {@code SessionTeardownTest}
 * pattern) — dying is exactly the "no orphans" acceptance signal, and only a real process lets a
 * test tell "died because quit landed" apart from "died because SIGTERM landed".
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SessionTeardownAdapterQuitTest {

    private ScriptedJsonRpcServer server;
    private IceAdapterConnection connection;
    private SubprocessManager sleeper;

    @AfterEach
    void tearDown() {
        if (sleeper != null) {
            sleeper.terminate();
        }
        if (connection != null) {
            connection.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** Starts the ScriptedJsonRpcServer, connects a real {@link IceAdapterConnection} to it. */
    private void connectAdapterRpc() throws Exception {
        server = new ScriptedJsonRpcServer();
        server.start();
        connection = new IceAdapterConnection(server.port());
        connection.connect().get(5, TimeUnit.SECONDS);
        server.awaitClient();
    }

    /** Starts the "adapter process" — a real, long-sleeping child. */
    private SubprocessManager startSleeper() throws Exception {
        sleeper =
                SubprocessManager.start(
                        new ProcessBuilder("sleep", "60"),
                        "adapter-quit-test",
                        Duration.ofSeconds(2));
        return sleeper;
    }

    private static LobbyConnection unconnectedLobby() {
        return new LobbyConnection(URI.create("ws://127.0.0.1:1"));
    }

    /**
     * A responsive adapter: teardown sends {@code quit}, the script answers and then kills the
     * "adapter" itself (simulating the real process quitting on its own), and teardown's SIGTERM
     * fallback never has to strike — the adapter is already dead from quit alone.
     */
    @Test
    void quitIsSentAndAdapterExitsBeforeSigtermIsNeeded() throws Exception {
        connectAdapterRpc();
        SubprocessManager adapter = startSleeper();

        // Answers the quit request as soon as it arrives, and kills the sleeper the way a real
        // adapter process quitting on its own would — so any death observed here came from quit,
        // not from SessionTeardown's own SIGTERM.
        Thread responder =
                new Thread(
                        () -> {
                            try {
                                String frame = server.pollReceived(5, TimeUnit.SECONDS);
                                if (!frame.contains("\"quit\"")) {
                                    return;
                                }
                                long id = extractId(frame);
                                adapter.terminate(Duration.ofSeconds(1));
                                server.send(
                                        "{\"jsonrpc\":\"2.0\",\"id\":"
                                                + id
                                                + ",\"result\":null}\n");
                            } catch (Exception ignored) {
                                // best effort; assertions below still catch a missed quit
                            }
                        },
                        "quit-responder");
        responder.setDaemon(true);
        responder.start();

        SessionTeardown teardown = new SessionTeardown(unconnectedLobby());
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(connection);

        teardown.run();

        assertFalse(adapter.isAlive(), "adapter must be dead once teardown returns");
        responder.join(1000);
    }

    /**
     * A silent adapter (never answers, never exits) still gets torn down and teardown still returns
     * promptly — the quit attempt is bounded and always falls through to the existing
     * SIGTERM/SIGKILL path.
     */
    @Test
    void quitWithNoResponseStillFallsThroughToTerminateAndStaysBounded() throws Exception {
        connectAdapterRpc();
        SubprocessManager adapter = startSleeper();

        SessionTeardown teardown = new SessionTeardown(unconnectedLobby());
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(connection);

        long start = System.nanoTime();
        teardown.run();
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        assertFalse(adapter.isAlive(), "a silent adapter must still be terminated");
        assertTrue(elapsed < 15, "teardown must stay bounded even when quit gets no response");

        // The adapter did receive the quit request; it simply chose (as scripted) not to answer.
        String frame = server.pollReceived(2, TimeUnit.SECONDS);
        assertTrue(frame.contains("\"quit\""), "quit must still have been sent");
    }

    private static long extractId(final String frame) {
        int idIndex = frame.indexOf("\"id\":");
        String tail = frame.substring(idIndex + 5);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length() && Character.isDigit(tail.charAt(i)); i++) {
            digits.append(tail.charAt(i));
        }
        return Long.parseLong(digits.toString());
    }
}
