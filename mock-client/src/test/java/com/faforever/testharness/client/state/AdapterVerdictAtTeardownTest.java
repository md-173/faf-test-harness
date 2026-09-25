package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The adapter's verdict when something other than its own exit ends the session (WBS-3.1.2.8-fix,
 * #438 and #452), driven through the real lifecycle and teardown: every route in the two cards'
 * acceptance criteria, each verdict sampled at the moment TERMINATED commits.
 *
 * <p>The sample is a dependent registered on the TERMINATED future before the trigger, as in {@code
 * AdapterCrashRecoveryTest}: it runs on the thread completing that future, inside {@code
 * commitTransition}, which is when {@code RunCommand}'s own wait is released. A verdict written any
 * later samples as absent.
 *
 * <p>The adapter is a real child process, since teardown's check reads the exit the reaper records.
 * Its JSON-RPC connection is a stand-in that holds the calls a test names and reports the
 * disconnect a test sets, which is how a death mid-call or a stream that stopped parsing is staged
 * without a real adapter. The game is a real child too.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class AdapterVerdictAtTeardownTest {

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

    /** A child that runs until teardown kills it. */
    private static final ProcessBuilder RUNS_UNTIL_KILLED = new ProcessBuilder("sleep", "60");

    /** The WARN a lost adapter is named by, whichever path found it. */
    private static final String ADAPTER_LOST_LINE = "ICE adapter exited abnormally";

    /** The WARN a live adapter's dropped link is named by (#452). */
    private static final String LINK_DROPPED_LINE =
            "ICE adapter JSON-RPC link dropped while the adapter kept running";

    @TempDir private Path tempDir;

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private DummyIceLauncher iceLauncher;
    private DummyGameLauncher gameLauncher;
    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level originalLevel;

    /** Run on the thread logging teardown's "waiting" line, before its wait starts. */
    private volatile Runnable onCheckWaiting = () -> {};

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender =
                new ListAppender<>() {
                    @Override
                    protected void append(final ILoggingEvent event) {
                        super.append(event);
                        if (event.getMessage().startsWith("ICE adapter closed its JSON-RPC link")) {
                            onCheckWaiting.run();
                        }
                    }
                };
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
        // The check's waiting line is DEBUG; set here so the tests that act on it work from an IDE
        // too, whatever the Gradle task's LOG_LEVEL.
        originalLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() throws Exception {
        root.setLevel(originalLevel);
        appender.stop();
        root.detachAppender(appender);
        if (gameLauncher != null && gameLauncher.getSubprocess() != null) {
            gameLauncher.getSubprocess().terminate(Duration.ofSeconds(1));
        }
        if (iceLauncher != null && iceLauncher.getSubprocess() != null) {
            iceLauncher.getSubprocess().terminate(Duration.ofSeconds(1));
        }
        try {
            lobby.close().get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Teardown's own close may still be waiting for the server's echo.
        }
        server.stop(1000);
    }

    /**
     * An adapter that dies while a {@code hostGame} call is in flight is lost (#438). The call
     * fails first and takes the session to TERMINATED from inside its action, which holds the state
     * machine, so the adapter's own exit can only arrive after teardown: before, that ran 0 every
     * time.
     */
    @Test
    void anAdapterThatDiesWithHostGameInFlightIsLost() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection("hostGame");
        MockClientLifecycle lifecycle = launched(rpc, new SessionTeardown(lobby));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        Thread poster = post(lifecycle, new HostGame(frame("HostGame", "scmp_007")));
        CompletableFuture<JsonNode> call = rpc.held("hostGame");
        int code = killAdapter();
        rpc.dropLink(null);
        call.completeExceptionally(closed(null));
        poster.join(15_000);

        assertEquals(new Found(false, true, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings(ADAPTER_LOST_LINE + " (code=" + code + ")"), warnings());
    }

    /** The same for {@code joinGame}, the other call awaited inside its action (#438). */
    @Test
    void anAdapterThatDiesWithJoinGameInFlightIsLost() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection("joinGame");
        MockClientLifecycle lifecycle = launched(rpc, new SessionTeardown(lobby));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        Thread poster = post(lifecycle, new JoinGame(frame("JoinGame", "Peer", 2)));
        CompletableFuture<JsonNode> call = rpc.held("joinGame");
        int code = killAdapter();
        rpc.dropLink(null);
        call.completeExceptionally(closed(null));
        poster.join(15_000);

        assertEquals(new Found(false, true, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings(ADAPTER_LOST_LINE + " (code=" + code + ")"), warnings());
    }

    /**
     * An adapter that dies while a {@code connectToPeer} call is in flight is lost, whichever event
     * ends the session (#438). That call is not awaited, so its failure and the adapter's exit
     * race, in the order a real death produces them. Either way the run reads 72 at commit with one
     * WARN, not two. The next case pins the route where the call's failure ends the session.
     */
    @Test
    void anAdapterThatDiesWithConnectToPeerInFlightIsLostWhicheverEventWins() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection("connectToPeer");
        MockClientLifecycle lifecycle = hosted(rpc, new SessionTeardown(lobby));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        lifecycle.post(new ConnectToPeer(frame("ConnectToPeer", "Peer", 2, true)));
        CompletableFuture<JsonNode> call = rpc.held("connectToPeer");
        int code = killAdapter();
        rpc.dropLink(null);
        call.completeExceptionally(closed(null));

        assertEquals(new Found(false, true, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings(ADAPTER_LOST_LINE + " (code=" + code + ")"), warnings());
    }

    /**
     * The {@code connectToPeer} route through teardown's check itself (#438): the call fails while
     * the adapter is still alive, so its {@code ShutdownRequested} is the only event that can end
     * the session, and the adapter is killed the moment the check says it is waiting. Only the
     * check can record 72 here; before it, this route read 0.
     */
    @Test
    void anAdapterDyingAsItsConnectToPeerFailsIsFoundByTeardown() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection("connectToPeer");
        MockClientLifecycle lifecycle = hosted(rpc, new SessionTeardown(lobby));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);
        onCheckWaiting = () -> iceLauncher.getSubprocess().terminate();

        lifecycle.post(new ConnectToPeer(frame("ConnectToPeer", "Peer", 2, true)));
        CompletableFuture<JsonNode> call = rpc.held("connectToPeer");
        rpc.dropLink(null);
        call.completeExceptionally(closed(null));

        assertEquals(new Found(false, true, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings(ADAPTER_LOST_LINE), warnings());
    }

    /**
     * An adapter whose game reports the lost link first is lost too (#438): the game's {@code 69}
     * ends the session, teardown finds the adapter's link closed and the adapter still dying, and
     * waits for its exit. The adapter is killed the moment the check says it is waiting, so its
     * exit lands inside the wait. The game's own line still says why it ended, and still names no
     * verdict: the code is keyed on the adapter, never on that {@code 69}.
     */
    @Test
    void anAdapterWhoseGameReportsTheLostLinkFirstIsLost() throws Exception {
        Path cue = tempDir.resolve("game-exits");
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection();
        MockClientLifecycle lifecycle =
                hosted(rpc, new SessionTeardown(lobby), exitingOnCue(69, cue));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);
        onCheckWaiting = () -> iceLauncher.getSubprocess().terminate();

        rpc.dropLink(null);
        Files.createFile(cue);

        assertEquals(new Found(false, true, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings(ADAPTER_LOST_LINE), warnings());
        assertEquals(1, warnings("mock-game exited with code 69"), warnings());
    }

    /**
     * A live adapter whose JSON-RPC stream stopped parsing, with a call in flight, failed the
     * session (#452): the call fails exactly as a dead adapter's would, and teardown finds the
     * adapter still running once its wait is up. One WARN names the dropped link and the parse
     * error. This waits out the production bound, two seconds.
     */
    @Test
    void aLiveAdapterThatDroppedItsLinkWithACallInFlightFailsTheSession() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection("hostGame");
        MockClientLifecycle lifecycle = launched(rpc, new SessionTeardown(lobby));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);
        JsonParseException parseError =
                new JsonParseException(null, "Unexpected character ('}' (code 125))");

        Thread poster = post(lifecycle, new HostGame(frame("HostGame", "scmp_007")));
        CompletableFuture<JsonNode> call = rpc.held("hostGame");
        rpc.dropLink(parseError);
        call.completeExceptionally(closed(parseError));
        poster.join(15_000);

        assertEquals(new Found(false, false, true), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(
                1,
                warnings(LINK_DROPPED_LINE + " (JsonParseException: Unexpected character"),
                warnings());
        assertEquals(0, warnings(ADAPTER_LOST_LINE), warnings());
    }

    /**
     * The same with no call in flight (#452): the link drops, and the session later ends some other
     * way, here the game exiting. Teardown still finds the adapter running without its link.
     */
    @Test
    void aLiveAdapterThatDroppedItsLinkWithNoCallInFlightFailsTheSession() throws Exception {
        Path cue = tempDir.resolve("game-exits");
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection();
        MockClientLifecycle lifecycle =
                hosted(rpc, new SessionTeardown(lobby), exitingOnCue(0, cue));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        rpc.dropLink(new JsonParseException(null, "Unexpected end-of-input"));
        Files.createFile(cue);

        assertEquals(new Found(false, false, true), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings(LINK_DROPPED_LINE), warnings());
    }

    /**
     * An adapter that quits cleanly under its own power, closing its link on the way, records
     * nothing: exit {@code 0} reads as the real client's "terminated normally", as it always has.
     */
    @Test
    void anAdapterThatQuitsCleanlyRecordsNothing() throws Exception {
        Path cue = tempDir.resolve("adapter-exits");
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection();
        MockClientLifecycle lifecycle =
                hosted(rpc, new SessionTeardown(lobby), exitingOnCue(0, cue), RUNS_UNTIL_KILLED);
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        rpc.dropLink(null);
        Files.createFile(cue);

        assertEquals(new Found(false, false, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(0, warnings(ADAPTER_LOST_LINE), warnings());
        assertEquals(0, warnings(LINK_DROPPED_LINE), warnings());
    }

    /**
     * A launch whose adapter died before its JSON-RPC port accepted a connection keeps its one
     * cause line (#437, #438): teardown finds that adapter dead with a non-zero code too, and must
     * not name it a second time. The lobby still gets one {@code GameState Ended} before the close,
     * as the real client sends after every {@code game_launch} it acted on (#462).
     */
    @Test
    void aFailedLaunchKeepsItsOneCauseLine() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection(new CompletableFuture<>());
        iceLauncher =
                new DummyIceLauncher(
                        MINIMAL_CONFIG, false, new ProcessBuilder("sh", "-c", "exit 3"));
        gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG, false, RUNS_UNTIL_KILLED);
        MockClientLifecycle lifecycle = lifecycle(rpc, new SessionTeardown(lobby));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(new Found(true, false, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(1, warnings("Could not "), "one cause line: " + warnings());
        assertEquals(0, warnings(ADAPTER_LOST_LINE), warnings());
        assertEquals(
                1, gameStateEndedBeforeClose(), "a failed launch still reports the game's end");
        assertEquals(0, warnings("failed to send GameState Ended"), warnings());
    }

    /**
     * A teardown a signal started records nothing about the adapter, however it finds it: the
     * signal kills the adapter itself (#438). Here the adapter is dead mid-call when the session
     * ends, the route that is otherwise a lost adapter.
     */
    @Test
    void aSignalledTeardownRecordsNoAdapterVerdict() throws Exception {
        ScriptedAdapterConnection rpc = new ScriptedAdapterConnection("hostGame");
        MockClientLifecycle lifecycle = launched(rpc, new SessionTeardown(lobby, () -> true));
        CompletableFuture<Found> atCommit = foundAtCommit(lifecycle);

        Thread poster = post(lifecycle, new HostGame(frame("HostGame", "scmp_007")));
        CompletableFuture<JsonNode> call = rpc.held("hostGame");
        killAdapter();
        rpc.dropLink(null);
        call.completeExceptionally(closed(null));
        poster.join(15_000);

        assertEquals(new Found(false, false, false), atCommit.get(15, TimeUnit.SECONDS));
        assertEquals(0, warnings(ADAPTER_LOST_LINE), warnings());
    }

    /**
     * The verdicts a run can report for its adapter, as sampled at a moment.
     *
     * @param launchFailed the launch never came up (#437)
     * @param adapterLost the adapter was lost (#406, #438)
     * @param sessionFailed the session failed after it came up, a dropped link included (#452)
     */
    private record Found(boolean launchFailed, boolean adapterLost, boolean sessionFailed) {}

    /**
     * Samples the verdicts at the moment TERMINATED commits; register before the trigger.
     *
     * @param lifecycle the lifecycle whose verdicts to sample
     * @return completes with the sample, taken on the thread that committed TERMINATED
     */
    private static CompletableFuture<Found> foundAtCommit(final MockClientLifecycle lifecycle) {
        return lifecycle
                .stateReached(ClientState.TERMINATED)
                .thenApply(
                        reached -> {
                            SessionVerdicts verdicts = lifecycle.verdicts();
                            return new Found(
                                    verdicts.launchFailed(),
                                    verdicts.adapterLost(),
                                    verdicts.sessionFailed());
                        });
    }

    /**
     * A lifecycle in STARTING_GAME: welcome received, adapter and game launched.
     *
     * @param rpc the adapter connection stand-in
     * @param teardown the session's teardown
     * @return the lifecycle
     */
    private MockClientLifecycle launched(
            final ScriptedAdapterConnection rpc, final SessionTeardown teardown) {
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, false, RUNS_UNTIL_KILLED);
        gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG, false, RUNS_UNTIL_KILLED);
        MockClientLifecycle lifecycle = lifecycle(rpc, teardown);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
        return lifecycle;
    }

    private MockClientLifecycle hosted(
            final ScriptedAdapterConnection rpc, final SessionTeardown teardown) throws Exception {
        return hosted(rpc, teardown, RUNS_UNTIL_KILLED, RUNS_UNTIL_KILLED);
    }

    private MockClientLifecycle hosted(
            final ScriptedAdapterConnection rpc,
            final SessionTeardown teardown,
            final ProcessBuilder game)
            throws Exception {
        return hosted(rpc, teardown, RUNS_UNTIL_KILLED, game);
    }

    /**
     * A lifecycle in HOSTING, its {@code hostGame} answered.
     *
     * @param rpc the adapter connection stand-in, which must not hold {@code hostGame}
     * @param teardown the session's teardown
     * @param adapter the adapter child
     * @param game the game child
     * @return the lifecycle
     */
    private MockClientLifecycle hosted(
            final ScriptedAdapterConnection rpc,
            final SessionTeardown teardown,
            final ProcessBuilder adapter,
            final ProcessBuilder game)
            throws Exception {
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, false, adapter);
        gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG, false, game);
        MockClientLifecycle lifecycle = lifecycle(rpc, teardown);
        CompletableFuture<Void> hosting = lifecycle.stateReached(ClientState.HOSTING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(frame("HostGame", "scmp_007")));
        hosting.get(15, TimeUnit.SECONDS);
        return lifecycle;
    }

    private MockClientLifecycle lifecycle(
            final ScriptedAdapterConnection rpc, final SessionTeardown teardown) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        return new MockClientLifecycle(
                MINIMAL_CONFIG, session, rpc, gameLauncher, iceLauncher, teardown);
    }

    /**
     * Posts {@code event} from a thread of its own, for an event whose action blocks on a held call
     * until the test releases it.
     *
     * @param lifecycle the lifecycle to post to
     * @param event the event
     * @return the posting thread
     */
    private static Thread post(final MockClientLifecycle lifecycle, final Event event) {
        Thread poster = new Thread(() -> lifecycle.post(event), "test-poster");
        poster.setDaemon(true);
        poster.start();
        return poster;
    }

    /**
     * SIGTERMs the adapter child and waits until the reaper has recorded its exit.
     *
     * @return the code it exited with
     */
    private int killAdapter() {
        SubprocessManager adapter = iceLauncher.getSubprocess();
        adapter.terminate();
        return adapter.exitCode().orElseThrow();
    }

    /**
     * What a call fails with once the adapter's connection has closed.
     *
     * @param error what closed it, or {@code null} for a clean end of the stream
     * @return the failure, as {@code IceAdapterConnection} builds it
     */
    private static IOException closed(final Throwable error) {
        return new IOException("ICE adapter connection closed (REMOTE_CLOSE)", error);
    }

    /**
     * A child that waits for {@code cue} to exist, then exits with {@code code}.
     *
     * @param code what it exits with
     * @param cue the file whose creation releases it
     * @return its builder
     */
    private static ProcessBuilder exitingOnCue(final int code, final Path cue) {
        return new ProcessBuilder(
                "sh",
                "-c",
                "while [ ! -e \"$1\" ]; do sleep 0.05; done; exit " + code,
                "sh",
                cue.toString());
    }

    /**
     * A lobby frame for the game, as the server sends it.
     *
     * @param command the frame's command
     * @param args its arguments
     * @return the frame
     */
    private static JsonNode frame(final String command, final Object... args) {
        return MAPPER.valueToTree(Map.of("command", command, "target", "game", "args", args));
    }

    /**
     * How many {@code GameState Ended} frames the lobby received before teardown closed it. Waits
     * for that close first: the server handles a connection's frames in order, so every frame sent
     * before a clean close is queued by then.
     *
     * @return the count
     * @throws Exception if the lobby did not close cleanly within five seconds
     */
    private long gameStateEndedBeforeClose() throws Exception {
        assertEquals(1000, server.awaitClose(5, TimeUnit.SECONDS), "teardown closes the lobby");
        long sent = 0;
        while (true) {
            JsonNode frame;
            try {
                frame = MAPPER.readTree(server.pollReceived(250, TimeUnit.MILLISECONDS));
            } catch (AssertionError none) {
                return sent;
            }
            if ("GameState".equals(frame.path("command").asText())
                    && "Ended".equals(frame.path("args").path(0).asText())) {
                sent++;
            }
        }
    }

    private List<ILoggingEvent> significantEvents() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                .toList();
    }

    /** How many WARN records start with {@code prefix}. */
    private long warnings(final String prefix) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().startsWith(prefix))
                .count();
    }

    /** The WARN and ERROR messages, for failure output. */
    private String warnings() {
        return "captured: "
                + significantEvents().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Adapter connection stand-in: connects as told, answers every call at once except the ones a
     * test names, which it holds for the test to fail, and reports the disconnect a test sets.
     */
    private static final class ScriptedAdapterConnection extends IceAdapterConnection {

        private final CompletableFuture<Void> connected;
        private final Set<String> held;

        /** Per held method: completes with the call's own future once the lifecycle makes it. */
        private final Map<String, CompletableFuture<CompletableFuture<JsonNode>>> heldCalls =
                new ConcurrentHashMap<>();

        private volatile DisconnectEvent ended;

        ScriptedAdapterConnection(final String... held) {
            this(CompletableFuture.completedFuture(null), held);
        }

        ScriptedAdapterConnection(final CompletableFuture<Void> connected, final String... held) {
            super(1);
            this.connected = connected;
            this.held = Set.of(held);
        }

        @Override
        public CompletableFuture<Void> connect() {
            return connected;
        }

        @Override
        public CompletableFuture<JsonNode> call(final String method, final Object... params) {
            if (!held.contains(method)) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<JsonNode> call = new CompletableFuture<>();
            heldCalls.computeIfAbsent(method, ignored -> new CompletableFuture<>()).complete(call);
            return call;
        }

        @Override
        public Optional<DisconnectEvent> disconnectEvent() {
            return Optional.ofNullable(ended);
        }

        @Override
        public void registerNotification(final String name, final Consumer<JsonNode> handler) {}

        @Override
        public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

        @Override
        public void close() {}

        /**
         * The held call to {@code method}, once the lifecycle has made it.
         *
         * @param method the call's method
         * @return its future, for the test to fail
         * @throws Exception if the lifecycle does not make the call within ten seconds
         */
        CompletableFuture<JsonNode> held(final String method) throws Exception {
            return heldCalls
                    .computeIfAbsent(method, ignored -> new CompletableFuture<>())
                    .get(10, TimeUnit.SECONDS);
        }

        /**
         * Records that the adapter closed the link from its side, as the reader would.
         *
         * @param error what closed it, or {@code null} for a clean end of the stream
         */
        void dropLink(final Throwable error) {
            ended = new DisconnectEvent(DisconnectReason.REMOTE_CLOSE, error);
        }
    }
}
