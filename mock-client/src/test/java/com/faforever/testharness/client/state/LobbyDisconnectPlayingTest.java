package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectEvent;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectReason;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers lobby-disconnect handling during PLAYING (#193): unlike every setup state, where losing
 * the lobby means the session cannot proceed and tears down, PLAYING plays on. This mirrors the
 * official client — {@code FafServerAccessor} auto-reconnects and the game is never killed, because
 * established peer connections are peer-to-peer and the lobby is only the signalling relay. The
 * harness defers reconnect (R40) but keeps the session alive; it still ends deterministically
 * through the mock game's own exit.
 */
final class LobbyDisconnectPlayingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static final JsonNode HOST_GAME_MESSAGE;

    static {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        HOST_GAME_MESSAGE = node;
    }

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;

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
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some tests close the underlying socket already
            }
        }
        server.stop(1000);
    }

    private static Disconnected simulatedLobbyDrop() {
        return new Disconnected(
                new DisconnectEvent(
                        DisconnectReason.ABRUPT_CLOSE, 0, null, new RuntimeException("dropped")));
    }

    @Test
    void disconnectDuringPlayingStaysAndSessionEndsViaGameExit() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // Game exits on its own deterministic schedule, well after the disconnect below.
        DummyGameLauncher gameLauncher =
                new DummyGameLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "1"));
        DummyIceLauncher iceLauncher =
                new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        SessionTeardown teardown = new SessionTeardown(lobby);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        gameLauncher,
                        iceLauncher,
                        teardown);

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        // Lobby loss mid-game: logged, no transition, no teardown.
        lifecycle.post(simulatedLobbyDrop());
        assertEquals(ClientState.PLAYING, lifecycle.getState());
        assertTrue(gameLauncher.getSubprocess().isAlive());

        // Session still ends on its own via the game's own exit (GameExited), teardown runs once.
        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);
        assertFalse(gameLauncher.getSubprocess().isAlive());
        assertFalse(iceLauncher.getSubprocess().isAlive());
    }

    @Test
    void disconnectedWhileTerminatedIsSilentAndDoesNotRepeatTeardown() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher =
                new DummyGameLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        DummyIceLauncher iceLauncher =
                new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "10"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        lifecycle.shutdown();
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertFalse(gameLauncher.getSubprocess().isAlive());
        assertFalse(iceLauncher.getSubprocess().isAlive());

        // Self-inflicted noise: teardown closing the lobby fires this same event after the state
        // machine is already in TERMINATED. Must not error, transition, or re-run teardown.
        lifecycle.post(simulatedLobbyDrop());
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertFalse(gameLauncher.getSubprocess().isAlive());
        assertFalse(iceLauncher.getSubprocess().isAlive());
    }

    @Test
    void disconnectDuringSetupStateStillTearsDownRegression() throws Exception {
        // Regression: setup-state teardown-on-disconnect (pre-existing behaviour) must be
        // unaffected by registering the PLAYING/TERMINATED self-loops above.
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(null));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        lifecycle.post(simulatedLobbyDrop());
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }
}
