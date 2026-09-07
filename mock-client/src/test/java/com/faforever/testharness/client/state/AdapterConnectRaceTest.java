package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The adapter bring-up gives up when the adapter process dies (WBS-3.1.3.3-fix, #266).
 *
 * <p>{@code launchGame} waits for the adapter's JSON-RPC socket from inside a transition action,
 * and {@code StateMachine.receiveEvent} is synchronized, so everything arriving meanwhile queues
 * behind it. On the happy path that is invisible. It matters when the adapter never binds: the
 * connect retries for its full budget — 20 s since WBS-3.1.2.7 widened it — and a failed launch is
 * noticed that much late, with {@code AdapterExited} sitting queued for the same window.
 *
 * <p>The case the budget exists for is an adapter that dies on the way up, which is what a usage
 * error produces — and it exits {@code 0} while doing so, so nothing else about the run says it is
 * gone. Racing the connect against the process's own exit future ends the wait then and there.
 *
 * <p>The connect here <em>never</em> completes, so without the race this test does not fail — it
 * hangs, which is why the class carries a {@link Timeout}. A budget-shaped wait would have been the
 * weaker test: it would pass on a slow machine for the wrong reason.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class AdapterConnectRaceTest {

    /** Comfortably shorter than the real 20 s connect budget, so a regression is unambiguous. */
    private static final int GIVE_UP_SECONDS = 10;

    private static final MockClientConfig MINIMAL_CONFIG =
            new MockClientConfig(
                    URI.create("wss://lobby.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
                    "00000000-0000-0000-0000-000000000000",
                    "0.0.0-mock",
                    "faf-test-harness",
                    Optional.empty(),
                    Path.of("/bin/faf-ice-adapter"),
                    Path.of("/bin/mock-game"),
                    0,
                    0,
                    0,
                    0,
                    5,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    Optional.empty(),
                    Optional.empty());

    private static final GameConfig MINIMAL_GAME_CONFIG =
            new GameConfig(
                    42,
                    "faf",
                    "Test Game Name",
                    0,
                    "custom",
                    "global",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private DummyIceLauncher iceLauncher;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (iceLauncher != null && iceLauncher.getSubprocess() != null) {
            iceLauncher.getSubprocess().terminate();
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Some paths have already closed it.
            }
        }
        server.stop(1000);
    }

    /**
     * An adapter that exits immediately, against a connect that never completes. Without the race
     * the transition action waits for a socket that will never open; with it, the process's death
     * is what ends the wait, and the failed transition drives the FSM to TERMINATED.
     */
    @Test
    void anAdapterThatDiesEndsTheConnectWaitAtOnce() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // `true` exits 0 immediately — the same shape as the adapter's own usage-error exit, which
        // is what makes this the case the race exists for rather than a contrived kill.
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("true"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new NeverConnectingIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        new DummyGameLauncher(MINIMAL_CONFIG),
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        long start = System.nanoTime();
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        lifecycle.stateReached(ClientState.TERMINATED).get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
        long elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(
                elapsedSeconds < GIVE_UP_SECONDS,
                "the wait must end with the process, not with the connect budget; took "
                        + elapsedSeconds
                        + "s");
    }

    /** A connection whose connect never resolves, so only the process exit can end the wait. */
    private static final class NeverConnectingIceAdapterConnection extends IceAdapterConnection {

        NeverConnectingIceAdapterConnection(final int port) {
            super(port);
        }

        @Override
        public CompletableFuture<Void> connect() {
            return new CompletableFuture<>();
        }
    }
}
