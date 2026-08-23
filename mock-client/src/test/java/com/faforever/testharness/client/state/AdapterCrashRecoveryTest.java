package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Covers ICE adapter crash recovery (#214): an adapter death in any post-launch state drives the
 * FSM to TERMINATED, and the classification line (INFO clean / WARN crash / DEBUG teardown-owned)
 * matches the real client's split. Uses a real short-lived child process for the adapter (the
 * {@code GameProcessTest} pattern) so exit codes and teardown are observed for real, not mocked.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class AdapterCrashRecoveryTest {

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
    private static final String LAUNCHING_NOTIFICATION =
            "{\"method\": \"onGpgNetMessageReceived\","
                    + "\"params\": [\"GameState\", [\"Launching\"]]}";

    static {
        var node = MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        HOST_GAME_MESSAGE = node;
    }

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private DummyIceLauncher iceLauncher;
    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    // Stashed by RecordingIceAdapterConnection's constructor so firePlayingNotification can reach
    // it after it's been handed off to the lifecycle.
    private RecordingIceAdapterConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // Lifecycle and process-exit callbacks can append while the test thread reads the log.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        appender.stop();
        root.detachAppender(appender);
        if (iceLauncher != null && iceLauncher.getSubprocess() != null) {
            iceLauncher.getSubprocess().terminate(Duration.ofSeconds(1));
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
    void adapterKilledDuringHostingReachesTerminatedAndWarns() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();

        int code = killAdapter();

        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        ILoggingEvent warn =
                findEvent(
                        e ->
                                e.getLevel() == Level.WARN
                                        && e.getFormattedMessage()
                                                .contains("ICE adapter exited abnormally"));
        assertTrue(
                warn.getFormattedMessage().contains(String.valueOf(code)),
                "WARN must carry the actual exit code; got: " + warn.getFormattedMessage());
    }

    @Test
    void adapterKilledDuringPlayingReachesTerminatedAndWarns() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();
        firePlayingNotification();
        assertEquals(ClientState.PLAYING, lifecycle.getState());

        int code = killAdapter();

        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        ILoggingEvent warn =
                findEvent(
                        e ->
                                e.getLevel() == Level.WARN
                                        && e.getFormattedMessage()
                                                .contains("ICE adapter exited abnormally"));
        assertTrue(
                warn.getFormattedMessage().contains(String.valueOf(code)),
                "WARN must carry the actual exit code; got: " + warn.getFormattedMessage());
    }

    @Test
    void cleanShutdownLogsNoAdapterCrashWarning() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();
        assertTrue(iceLauncher.getSubprocess().isAlive());

        lifecycle.shutdown();

        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);
        // Teardown terminated the adapter as part of shutdown; give the exit future a moment to
        // fire and be classified before asserting no crash warning was logged.
        iceLauncher.getSubprocess().onExit().get(5, TimeUnit.SECONDS);
        assertFalse(iceLauncher.getSubprocess().isAlive());

        boolean crashWarned =
                appender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage()
                                                        .contains("ICE adapter exited abnormally"));
        assertFalse(crashWarned, "a teardown-initiated adapter exit must not log a crash warning");
    }

    @Test
    void lateAdapterExitedAfterTerminatedIsNoOp() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();
        lifecycle.shutdown();
        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);

        lifecycle.post(new AdapterExited(1));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    /** Kills the running adapter subprocess and returns the exit code it actually produced. */
    private int killAdapter() throws Exception {
        SubprocessManager adapter = iceLauncher.getSubprocess();
        adapter.terminate();
        int code = adapter.onExit().get(5, TimeUnit.SECONDS);
        assertNotEquals(0, code, "a killed process must not report a clean exit");
        return code;
    }

    /** Brings a fresh lifecycle up through LaunchGame + HostGame, i.e. to HOSTING. */
    private MockClientLifecycle hostedLifecycle() {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        iceLauncher =
                new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("sleep", "60"));
        RecordingIceAdapterConnection iceConn =
                new RecordingIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        assertEquals(ClientState.HOSTING, lifecycle.getState());
        return lifecycle;
    }

    private void firePlayingNotification() throws Exception {
        connection.fireNotification(
                "onGpgNetMessageReceived", MAPPER.readTree(LAUNCHING_NOTIFICATION));
    }

    private ILoggingEvent findEvent(final Predicate<ILoggingEvent> matcher) {
        for (ILoggingEvent e : appender.list) {
            if (matcher.test(e)) {
                return e;
            }
        }
        fail("no log event matched. captured: " + appender.list);
        throw new AssertionError("unreachable");
    }

    /** Adapter-connection stub supporting notification fan-out (mirrors PlayingTransitionTest). */
    private final class RecordingIceAdapterConnection extends IceAdapterConnection {
        private final Map<String, List<Consumer<JsonNode>>> notificationHandlers = new HashMap<>();

        RecordingIceAdapterConnection(int port) {
            super(port);
            connection = this;
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
            notificationHandlers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(handler);
        }

        public void fireNotification(final String name, JsonNode value) {
            for (Consumer<JsonNode> handler : notificationHandlers.getOrDefault(name, List.of())) {
                handler.accept(value);
            }
        }

        @Override
        public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

        @Override
        public void close() {}
    }
}
