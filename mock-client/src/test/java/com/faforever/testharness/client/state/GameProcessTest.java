package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.LaunchIdentity;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers game-process ownership (WBS-3.1.2.4): the lifecycle owns the launched game and exposes a
 * single exit signal via {@link MockClientLifecycle#gameExit()}, and registers the process with
 * {@link SessionTeardown} at launch. Uses real short-lived child processes (the {@code
 * SessionTeardownTest} pattern) so exit codes and termination are observed for real, not mocked.
 */
final class GameProcessTest {

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
                    Optional.empty(),
                    0);

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
    private ChildGameLauncher gameLauncher;

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
        if (gameLauncher != null && gameLauncher.manager != null) {
            gameLauncher.manager.terminate(Duration.ofSeconds(1));
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some tests close the underlying socket already
            }
        }
        server.stop(1000);
    }

    @Test
    void exitSignalDeliversCleanExitCode() throws Exception {
        gameLauncher = new ChildGameLauncher("sh", "-c", "exit 7");
        MockClientLifecycle lifecycle = lifecycleWith(gameLauncher, teardown());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(7, lifecycle.gameExit().get(5, TimeUnit.SECONDS));
    }

    @Test
    void exitSignalDeliversKilledProcessExitCode() throws Exception {
        gameLauncher = new ChildGameLauncher("sleep", "60");
        MockClientLifecycle lifecycle = lifecycleWith(gameLauncher, teardown());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        gameLauncher.manager.terminate();

        int delivered = lifecycle.gameExit().get(5, TimeUnit.SECONDS);
        assertEquals(gameLauncher.manager.exitCode().getAsInt(), delivered);
        // A killed process must be distinguishable from a clean exit (R41 relies on this).
        assertNotEquals(0, delivered);
    }

    @Test
    void subscribingAfterExitStillDeliversTheCode() throws Exception {
        gameLauncher = new ChildGameLauncher("sh", "-c", "exit 3");
        MockClientLifecycle lifecycle = lifecycleWith(gameLauncher, teardown());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        // Let the process die before anyone subscribes.
        gameLauncher.manager.onExit().get(5, TimeUnit.SECONDS);

        CompletableFuture<Integer> late = lifecycle.gameExit();
        assertEquals(3, late.get(5, TimeUnit.SECONDS));

        // Independent copies observe the same single completion.
        assertEquals(3, lifecycle.gameExit().get(5, TimeUnit.SECONDS));
    }

    @Test
    void gameProcessIsRegisteredForTeardownAtLaunch() throws Exception {
        gameLauncher = new ChildGameLauncher("sleep", "60");
        SessionTeardown teardown = teardown();
        MockClientLifecycle lifecycle = lifecycleWith(gameLauncher, teardown);

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        assertTrue(gameLauncher.manager.isAlive());

        teardown.run();

        // The teardown could only have terminated the game if launch registered it.
        assertFalse(gameLauncher.manager.isAlive());
        lifecycle.gameExit().get(5, TimeUnit.SECONDS);
    }

    private SessionTeardown teardown() {
        return new SessionTeardown(lobby);
    }

    private MockClientLifecycle lifecycleWith(
            ChildGameLauncher launcher, SessionTeardown teardown) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        return new MockClientLifecycle(
                MINIMAL_CONFIG,
                session,
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                launcher,
                new DummyIceLauncher(MINIMAL_CONFIG),
                teardown);
    }

    /** Launches a real child with the given argv and retains the manager for assertions. */
    private final class ChildGameLauncher extends MockGameLauncher {
        private final List<String> argv;
        private SubprocessManager manager;

        ChildGameLauncher(String... argv) {
            super(MINIMAL_CONFIG);
            this.argv = List.of(argv);
        }

        @Override
        public SubprocessManager start(LaunchIdentity identity) throws MockGameLaunchException {
            try {
                manager =
                        SubprocessManager.start(
                                new ProcessBuilder(argv), "TEST GAME", Duration.ofSeconds(5));
                return manager;
            } catch (IOException e) {
                throw new MockGameLaunchException(e.getMessage());
            }
        }
    }
}
