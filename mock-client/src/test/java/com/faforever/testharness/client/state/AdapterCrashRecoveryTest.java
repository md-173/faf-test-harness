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
                    URI.create("wss://ws.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
                    Optional.empty(),
                    Optional.of("00000000-0000-0000-0000-000000000000"),
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
                    Optional.empty(),
                    0,
                    0,
                    -1);

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
    private Level originalLevel;

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
        // The teardown-owned classification this class waits on is DEBUG, and the level is set
        // here rather than inherited from the Gradle task's LOG_LEVEL so the same wait works from
        // an IDE. Without it those records never reach the appender and the wait would expire
        // instead of asserting.
        originalLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() throws Exception {
        root.setLevel(originalLevel);
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

    /**
     * The captured records worth printing in a failure message. The appender is on the root logger
     * and the test task now runs at DEBUG, so interpolating the whole list buries the assertion.
     *
     * @return only the WARN and ERROR records
     */
    private java.util.List<ILoggingEvent> significantEvents() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                .toList();
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
        assertTrue(
                lifecycle.verdicts().adapterLost(),
                "an adapter killed while HOSTING is a lost adapter");
        assertFalse(
                lifecycle.verdicts().launchFailed(),
                "and not a failed launch: that one came up (#437)");
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
        // The PLAYING edge carries the exit status too (WBS-3.1.2.8-fix, #406), not only the
        // warning. Both come from the one branch in onAdapterExited, so this pins that they stay
        // together.
        assertTrue(
                lifecycle.verdicts().adapterLost(),
                "an adapter killed while PLAYING is a lost adapter");
    }

    /**
     * The exit status is decided before anything can read it (WBS-3.1.2.8-fix, #406).
     *
     * <p>This is the property #357's attempt lacked. That one keyed the code on the game's reported
     * status, written on a {@code CompletableFuture} continuation, so {@code RunCommand} could be
     * released by the adapter's death before the verdict existed and the same scenario exited 69 or
     * 0 run to run.
     *
     * <p>Asserting {@code adapterLost()} after a second {@code get()} would prove little, because
     * by then a late write has had time to land. So the verdict is sampled by a dependent
     * registered on the TERMINATED future <em>before</em> the adapter is killed. In the ordinary
     * course that dependent runs on the thread completing the future, inside {@code
     * commitTransition}, which is the moment {@code RunCommand}'s own {@code get()} is released, so
     * a verdict written any later than the transition action samples as {@code false}.
     *
     * <p>The assertion waits on the dependent's own future rather than on the TERMINATED one. The
     * JDK unparks a thread waiting on a future before it has necessarily run every other dependent,
     * so reading a flag the dependent sets could see it still unset on a correct build.
     *
     * <p>Teardown reaps the game from TERMINATED's entry hook, which runs before that commit, so
     * the game's exit is already reaped here while its classification is still outstanding on
     * another thread. That is the ordering this asserts is irrelevant to the adapter's verdict.
     */
    @Test
    void theExitVerdictIsSetBeforeTerminatedCompletes() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();
        CompletableFuture<Boolean> atCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().adapterLost());

        killAdapter();

        assertTrue(
                atCommit.get(5, TimeUnit.SECONDS),
                "the adapter verdict must already be set when TERMINATED commits, not written"
                        + " afterwards. captured: "
                        + significantEvents());
        assertTrue(lifecycle.verdicts().adapterLost(), "and it must still read true afterwards");
    }

    /**
     * A teardown-initiated adapter exit is not a crash, and this waits for the classification
     * before saying so.
     *
     * <p>The wait is the test, not housekeeping. Until #406 this asserted as soon as the adapter
     * process had exited, but the event is posted to the FSM asynchronously ({@code
     * adapterExit().thenAcceptAsync(...)}), so the assertion usually ran before anything had
     * classified the exit at all: measured, the test passed with the {@code teardown.hasRun()}
     * branch of {@code onAdapterExited} deleted, which is the defect it exists to catch.
     *
     * <p>It waits on {@code logAdapterExitAfterTeardown}'s own trailing record rather than on the
     * classification line, because that record is emitted whichever branch classified the exit. A
     * regression therefore fails the assertion below instead of expiring the wait. The match names
     * the adapter explicitly: {@code logGameExitAfterTeardown}'s trailing record reads {@code
     * mock-game exited after session teardown}, and the game's exit is queued on the same monitor,
     * so a looser match can end the wait with the adapter still unclassified.
     */
    @Test
    void cleanShutdownLogsNoAdapterCrashWarning() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();
        assertTrue(iceLauncher.getSubprocess().isAlive());

        lifecycle.shutdown();

        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);
        int code = iceLauncher.getSubprocess().onExit().get(5, TimeUnit.SECONDS);
        assertFalse(iceLauncher.getSubprocess().isAlive());
        awaitEvent(
                e -> e.getFormattedMessage().contains("ICE adapter exited after session teardown"));

        boolean crashWarned =
                appender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage()
                                                        .contains("ICE adapter exited abnormally"));
        assertFalse(
                crashWarned,
                "a teardown-initiated adapter exit (code="
                        + code
                        + ") must not log a crash warning. captured: "
                        + significantEvents());
        assertFalse(
                lifecycle.verdicts().adapterLost(),
                "nor may it set the exit verdict, or every clean shutdown would report a lost"
                        + " adapter");
    }

    /**
     * A non-zero adapter exit delivered after teardown is a no-op: no unregistered-event warning,
     * no crash warning, and no exit verdict.
     *
     * <p>The deterministic guard for the {@code teardown.hasRun()} branch of {@code
     * onAdapterExited}, and the test its javadoc points at. {@code post} delivers the event
     * synchronously on this thread, so unlike the sibling test above there is nothing to wait for
     * and nothing to race: delete that branch and this fails outright.
     */
    @Test
    void lateAdapterExitedAfterTerminatedIsNoOp() throws Exception {
        MockClientLifecycle lifecycle = hostedLifecycle();
        lifecycle.shutdown();
        lifecycle.stateReached(ClientState.TERMINATED).get(5, TimeUnit.SECONDS);

        lifecycle.post(new AdapterExited(1));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        // #252: the state was already right before the TERMINATED self-loop existed — the IGNORE
        // policy saw to that. What was wrong was the WARN it left behind on every clean run.
        assertFalse(
                appender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage()
                                                        .contains("No matching transitions")),
                "a post-teardown adapter exit must be a deliberate no-op, not an "
                        + "unregistered-event warning. captured: "
                        + significantEvents());
        assertFalse(
                appender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage()
                                                        .contains("ICE adapter exited abnormally")),
                "nor a crash warning, whatever the code it carries. captured: "
                        + significantEvents());
        assertFalse(
                lifecycle.verdicts().adapterLost(),
                "nor may it set the exit verdict after TERMINATED has already been observed, which"
                        + " is the race that removed the game-keyed code from #357");
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

    /**
     * Waits for a record the FSM emits from an asynchronous continuation, polling every 50 ms for
     * up to 5 s, well inside the class timeout. Used where the signal being asserted on is only
     * observable once a post-teardown event has actually been delivered to the machine.
     *
     * @param matcher the record being waited for
     * @return that record
     */
    private ILoggingEvent awaitEvent(final Predicate<ILoggingEvent> matcher) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            for (ILoggingEvent event : appender.list) {
                if (matcher.test(event)) {
                    return event;
                }
            }
            Thread.sleep(50);
        }
        fail("no log event matched within 5s. captured: " + significantEvents());
        throw new AssertionError("unreachable");
    }

    private ILoggingEvent findEvent(final Predicate<ILoggingEvent> matcher) {
        for (ILoggingEvent e : appender.list) {
            if (matcher.test(e)) {
                return e;
            }
        }
        fail("no log event matched. captured: " + significantEvents());
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
