package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.client.config.GameHostConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LifecycleTest {
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

    private static final JsonNode HOST_GAME_MESSAGE;
    private static final JsonNode JOIN_GAME_MESSAGE;

    // Static initialization block to build JsonNode messages.
    static {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        HOST_GAME_MESSAGE = node;

        node = MAPPER.createObjectNode().put("command", "JoinGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("test").add(1));
        JOIN_GAME_MESSAGE = node;
    }

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;

    /**
     * One lifecycle handed out by {@link #lifecycleWithConfig}, together with the two launchers it
     * was built on — kept so teardown can both drive the lifecycle to TERMINATED and check that the
     * children those launchers spawned actually died with it.
     *
     * @param lifecycle the lifecycle under test
     * @param game the game launcher it was built with
     * @param ice the ICE adapter launcher it was built with
     */
    private record Launched(
            MockClientLifecycle lifecycle, DummyGameLauncher game, DummyIceLauncher ice) {}

    /**
     * Everything {@link #lifecycleWithConfig} handed out, in creation order. Tests here build more
     * than one — {@link #disconnection()} builds five — and each that posts {@code LaunchGame}
     * spawns a game and an ICE adapter child.
     */
    private final List<Launched> launched = new ArrayList<>();

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
        // Drive every lifecycle to TERMINATED before the lobby goes (#303). Its entry action runs
        // SessionTeardown, which is what actually reaps the two subprocesses each LaunchGame
        // spawned; without it they lived until SubprocessRegistry's JVM-exit hook SIGTERMed them as
        // the forked test JVM died, long after Gradle had moved on to another task. That produced
        // four "exited abnormally with exit code 143" warnings on every green build, filed under
        // whichever task banner happened to be current.
        //
        // Before the lobby close below, not after: teardown closes the lobby itself, and running
        // it second found the socket already shut and warned about it. Reverse order so the
        // lifecycle that owns the live session is torn down first, and best-effort throughout —
        // a test that already drove one to TERMINATED, or closed the socket under it, must not
        // fail in teardown.
        for (int i = launched.size() - 1; i >= 0; i--) {
            try {
                launched.get(i).lifecycle().shutdown();
            } catch (RuntimeException ignored) {
                // teardown is best effort; the assertions have already run
            }
        }

        // Then say so out loud. The default stub blocks on stdin and never exits by itself, so a
        // test that spawns one and does not tear it down leaks silently — the JVM-exit hook reaps
        // it eventually and the only trace is a warning under some later Gradle task. Asserting it
        // here turns the next occurrence into a failure in the test that caused it, which is the
        // half of #303 that stops this coming back.
        for (Launched entry : launched) {
            assertChildDied(entry.game().getSubprocess(), "mock-game");
            assertChildDied(entry.ice().getSubprocess(), "ICE adapter");
        }
        launched.clear();

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
    void happyPath() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());

        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        assertEquals(ClientState.HOSTING, lifecycle.getState());

        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        lifecycle.post(new GameExited(0));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void sendsGameHostOnIdleEntryWhenConfigured() throws Exception {
        GameHostConfig hostConfig =
                new GameHostConfig(
                        "Test game",
                        "scmp_007",
                        "faf",
                        "public",
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        Map.of());
        MockClientLifecycle lifecycle =
                lifecycleWithConfig(configWithHostConfig(Optional.of(hostConfig)));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        String received = server.pollReceived(2, TimeUnit.SECONDS);
        JsonNode parsed = MAPPER.readTree(received);
        assertEquals("game_host", parsed.get("command").asText());
        assertEquals("Test game", parsed.get("title").asText());
    }

    @Test
    void doesNotSendGameHostWhenNotConfigured() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        assertThrows(
                AssertionError.class,
                () -> server.pollReceived(500, TimeUnit.MILLISECONDS),
                "no game_host should be sent when the mock client was not configured to host");
    }

    @Test
    void authFailure() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new AuthFailed(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void disconnection() throws Exception {
        MockClientLifecycle lifecycle = defaultLifecycle();
        assertEquals(ClientState.CONNECTING, lifecycle.getState());

        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new Disconnected(null));
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle = defaultLifecycle();
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        lifecycle.post(new Disconnected(null));
        // When a game has already started, communication occurs peer-to-peer and lobby server is
        // not needed. Disconnection does not cause termination.
        assertEquals(ClientState.PLAYING, lifecycle.getState());
    }

    /**
     * Asserts one launched child is gone. {@link SessionTeardown} terminates synchronously —
     * SIGTERM then, if needed, SIGKILL — so by the time {@code shutdown()} has returned for every
     * lifecycle there is nothing left to wait for and no budget to pick.
     *
     * @param child the subprocess handle, or {@code null} if this launcher was never asked to start
     * @param label human-readable name for the failure message
     */
    private static void assertChildDied(final SubprocessManager child, final String label) {
        if (child == null) {
            return;
        }
        assertFalse(
                child.isAlive(),
                label + " child was still running after teardown, so it will outlive this test");
    }

    /**
     * Copies {@link #MINIMAL_CONFIG} with {@code hostConfig} overridden — used by host-on-IDLE
     * tests that need a config distinct from the shared minimal fixture.
     */
    private static MockClientConfig configWithHostConfig(Optional<GameHostConfig> hostConfig) {
        return new MockClientConfig(
                MINIMAL_CONFIG.lobbyWebSocketUrl(),
                MINIMAL_CONFIG.oauthTokenUrl(),
                MINIMAL_CONFIG.oauthAuthEndpoint(),
                MINIMAL_CONFIG.oauthRedirectUri(),
                MINIMAL_CONFIG.oauthScopes(),
                MINIMAL_CONFIG.oauthClientId(),
                MINIMAL_CONFIG.oauthRefreshTokenFile(),
                MINIMAL_CONFIG.uniqueId(),
                MINIMAL_CONFIG.clientVersion(),
                MINIMAL_CONFIG.userAgent(),
                MINIMAL_CONFIG.uidBinaryPath(),
                MINIMAL_CONFIG.iceAdapterBinaryPath(),
                MINIMAL_CONFIG.mockGameBinaryPath(),
                MINIMAL_CONFIG.iceAdapterRpcPort(),
                MINIMAL_CONFIG.iceAdapterGpgNetPort(),
                MINIMAL_CONFIG.iceAdapterLobbyPort(),
                MINIMAL_CONFIG.iceAdapterGameId(),
                MINIMAL_CONFIG.mockGameLaunchDelaySeconds(),
                MINIMAL_CONFIG.logLevel(),
                MINIMAL_CONFIG.logFile(),
                MINIMAL_CONFIG.playerIdOverride(),
                MINIMAL_CONFIG.playerLogin(),
                hostConfig,
                MINIMAL_CONFIG.joinConfig(),
                0);
    }

    /**
     * Builds a lifecycle and registers it for teardown. Every lifecycle in this class goes through
     * here, which is what makes {@link #tearDown()}'s guarantee — no child process outlives the
     * test method that started it — hold for tests that build several.
     *
     * @param config the configuration to build the lifecycle against
     * @return the registered lifecycle
     */
    private MockClientLifecycle lifecycleWithConfig(MockClientConfig config) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher game = new DummyGameLauncher(config);
        DummyIceLauncher ice = new DummyIceLauncher(config);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        config,
                        session,
                        new DummyIceAdapterConnection(config.iceAdapterRpcPort()),
                        game,
                        ice,
                        new SessionTeardown(lobby));
        launched.add(new Launched(lifecycle, game, ice));
        return lifecycle;
    }

    private MockClientLifecycle defaultLifecycle() {
        return lifecycleWithConfig(MINIMAL_CONFIG);
    }
}
