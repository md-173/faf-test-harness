package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Client end-of-session reporting (#192): a consumer on the ICE-notification fan-out (R36) that
 * filters {@code onGpgNetMessageReceived} for {@code GameEnded}, records the clean-end flag for
 * crash classification (R41), and arms a bounded safety net that is cancelled by the game's own
 * {@link GameExited} exit (R59b's teardown path — see {@link SessionTeardown}).
 */
final class GameEndReportingTest {
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

    /** Short enough to assert on directly in a unit test, long enough to avoid flakes. */
    private static final Duration TEST_SAFETY_NET_WINDOW = Duration.ofMillis(150);

    /**
     * A subprocess that hangs reading from its (never-written, never-closed) stdin pipe, so the
     * "game" and "ICE adapter" stay alive for the test's duration instead of racing GameExited in
     * on their own — every FSM transition here is driven explicitly by the test. {@code sort} with
     * no arguments blocks on stdin EOF on both Windows and POSIX, unlike the {@code echo}/{@code
     * sh} commands the other fixtures assume (which only exist on POSIX runners).
     */
    private static final ProcessBuilder HANGING_PROCESS = new ProcessBuilder("sort");

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private final List<DummyGameLauncher> gameLaunchers = new ArrayList<>();
    private final List<DummyIceLauncher> iceLaunchers = new ArrayList<>();

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
        // Tests that don't reach TERMINATED never run SessionTeardown, so the hanging "game"/"ICE
        // adapter" subprocesses they started would otherwise outlive the test.
        for (DummyGameLauncher launcher : gameLaunchers) {
            if (launcher.getSubprocess() != null) {
                launcher.getSubprocess().terminate(Duration.ofSeconds(1));
            }
        }
        for (DummyIceLauncher launcher : iceLaunchers) {
            if (launcher.getSubprocess() != null) {
                launcher.getSubprocess().terminate(Duration.ofSeconds(1));
            }
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
    void gameEndedRecordsCleanEndWithoutTeardown() throws Exception {
        FakeIceAdapterConnection iceConn = new FakeIceAdapterConnection(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle = playingLifecycle(iceConn, TEST_SAFETY_NET_WINDOW);

        assertFalse(lifecycle.isCleanEndSeen());

        iceConn.emitGpgNet("GameEnded");

        assertTrue(lifecycle.isCleanEndSeen());
        assertEquals(
                ClientState.PLAYING,
                lifecycle.getState(),
                "GameEnded alone must not initiate teardown");
    }

    @Test
    void normalCleanEndRunsTeardownExactlyOnce() throws Exception {
        FakeIceAdapterConnection iceConn = new FakeIceAdapterConnection(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle = playingLifecycle(iceConn, TEST_SAFETY_NET_WINDOW);

        iceConn.emitGpgNet("GameEnded");
        lifecycle.post(new GameExited(0));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(lifecycle.isCleanEndSeen());

        // The safety net must have been cancelled by GameExited: waiting past its window must not
        // move the (already terminal) state again or throw.
        Thread.sleep(TEST_SAFETY_NET_WINDOW.toMillis() * 3);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void safetyNetFiresOnlyWhenNoExitArrives() throws Exception {
        FakeIceAdapterConnection iceConn = new FakeIceAdapterConnection(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle = playingLifecycle(iceConn, TEST_SAFETY_NET_WINDOW);

        iceConn.emitGpgNet("GameEnded");
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        // Teardown's real subprocess termination (SIGTERM→grace→SIGKILL on the hanging "game" and
        // "ICE adapter") dominates this wait, not the (much shorter) safety-net window itself.
        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void safetyNetDoesNotFireWithoutGameEnded() throws Exception {
        FakeIceAdapterConnection iceConn = new FakeIceAdapterConnection(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle = playingLifecycle(iceConn, TEST_SAFETY_NET_WINDOW);

        Thread.sleep(TEST_SAFETY_NET_WINDOW.toMillis() * 3);

        assertEquals(ClientState.PLAYING, lifecycle.getState());
    }

    private MockClientLifecycle playingLifecycle(
            FakeIceAdapterConnection iceConn, Duration safetyNetWindow) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher =
                new DummyGameLauncher(MINIMAL_CONFIG, false, HANGING_PROCESS);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, false, HANGING_PROCESS);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby),
                        safetyNetWindow);

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(hostGameMessage()));
        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());
        return lifecycle;
    }

    private static JsonNode hostGameMessage() {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        return node;
    }

    /**
     * Captures every {@code onGpgNetMessageReceived} handler registered by the lifecycle (there are
     * two — the existing GameState/Launching one and #192's GameEnded one) so tests can fabricate
     * GPGNet frames without a real ICE adapter.
     */
    private static class FakeIceAdapterConnection extends IceAdapterConnection {
        private final Map<String, CopyOnWriteArrayList<Consumer<JsonNode>>> handlers =
                new HashMap<>();

        FakeIceAdapterConnection(MockClientConfig config) {
            super(config.iceAdapterRpcPort());
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<JsonNode> call(final String method, final Object... params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void registerNotification(final String name, final Consumer<JsonNode> handler) {
            handlers.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(handler);
        }

        @Override
        public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

        @Override
        public void close() {}

        /** Fabricates and dispatches an {@code onGpgNetMessageReceived(command, [])} frame. */
        void emitGpgNet(String command) {
            ObjectNode notification = MAPPER.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "onGpgNetMessageReceived");
            notification.putArray("params").add(command).addArray();
            List<Consumer<JsonNode>> registered =
                    handlers.getOrDefault("onGpgNetMessageReceived", new CopyOnWriteArrayList<>());
            for (Consumer<JsonNode> handler : registered) {
                handler.accept(notification);
            }
        }
    }
}
