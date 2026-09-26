package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceRpcException;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.LaunchIdentity;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

final class LifecycleSetupTest {
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

    // #211: tests that stop at STARTING_GAME/HOSTING/JOINING never reach TERMINATED, so
    // SessionTeardown never runs to reap the DummyGameLauncher/DummyIceLauncher's now-hanging
    // subprocess (see DummyGameLauncher's javadoc). Tracked here so tearDown can terminate them.
    private final List<DummyGameLauncher> gameLaunchers = new ArrayList<>();
    private final List<DummyIceLauncher> iceLaunchers = new ArrayList<>();

    /** Captures the lifecycle's own lines, for the failures that must name themselves once. */
    private ListAppender<ILoggingEvent> appender;

    private Logger lifecycleLogger;

    @BeforeEach
    void setUp() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        lifecycleLogger = context.getLogger(MockClientLifecycle.class);
        appender = new ListAppender<>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        lifecycleLogger.addAppender(appender);

        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        lifecycleLogger.detachAppender(appender);
        appender.stop();
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
    void launchGameTransition() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
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

        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
        assertTrue(gameLauncher.subprocessStarted());
        assertTrue(iceLauncher.subprocessStarted());

        Object[] lobbyInitMode = iceConn.receivedMessage("setLobbyInitMode");
        assertTrue(lobbyInitMode != null);
        assertEquals("normal", lobbyInitMode[0]);

        Object[] iceServers = iceConn.receivedMessage("setIceServers");
        assertTrue(iceServers != null);
        assertTrue(((Object[]) iceServers[0]).length == 0);
        assertFalse(
                lifecycle.verdicts().launchFailed(),
                "a launch that came up is not a failed launch");
    }

    @Test
    void launchPassesSessionIdentityToBothSubprocesses() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
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

        // welcome.me for the player, game_launch.uid for the game (WBS-3.1.2.9).
        LaunchIdentity expected =
                new LaunchIdentity(
                        SessionFixture.SESSION.id(),
                        SessionFixture.SESSION.login(),
                        MINIMAL_GAME_CONFIG.uid());
        assertEquals(expected, iceLauncher.getIdentity());
        assertEquals(expected, gameLauncher.getIdentity());

        // Guards the fixture itself. If SessionFixture ever drifted into matching the config, the
        // assertions above would pass while proving nothing.
        assertNotEquals(MINIMAL_CONFIG.playerLogin(), iceLauncher.getIdentity().login());
        assertNotEquals(MINIMAL_CONFIG.iceAdapterGameId(), iceLauncher.getIdentity().gameUid());
    }

    @Test
    void hostGameTransition() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
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

        Object[] hostGame = iceConn.receivedMessage("hostGame");
        assertTrue(hostGame != null);
        assertEquals("scmp_007", hostGame[0]);
    }

    @Test
    void joinGameTransition() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
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
        lifecycle.post(new JoinGame(JOIN_GAME_MESSAGE));

        Object[] joinGame = iceConn.receivedMessage("joinGame");
        assertTrue(joinGame != null);
        assertEquals("test", joinGame[0]);
        assertEquals(1, joinGame[1]);
    }

    @Test
    void gameLauncherFails() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG, true);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
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

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(
                lifecycle.verdicts().launchFailed(),
                "a game binary that cannot start fails the launch");
    }

    @Test
    void iceAdapterLauncherFails() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, true);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
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

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(
                lifecycle.verdicts().launchFailed(),
                "an adapter that cannot start fails the launch");
    }

    @Test
    void iceConnectionFailsOnConnection() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort(), true);
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

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(
                lifecycle.verdicts().launchFailed(),
                "an adapter that never connects fails the launch");
    }

    @ParameterizedTest
    @ValueSource(strings = {"setLobbyInitMode", "setIceServers"})
    void iceConnectionCallFails(final String method) throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        iceConn.setupCallFail(method, new IceRpcException(-32000, "refused"));
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

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(
                lifecycle.verdicts().launchFailed(),
                "an adapter that refuses " + method + " fails it too");
    }

    /**
     * A {@code hostGame} or {@code joinGame} call the adapter answers with an error, or does not
     * answer in time, fails the session with a verdict (WBS-3.1.3.3-fix, #445). The adapter was
     * still connected, so the call is the finding, and one WARN names it. Sampled the moment
     * TERMINATED commits, so a verdict written after the commit could not pass.
     *
     * @param method the call the adapter fails
     * @param errorAnswer whether it answers with an error, rather than not in time
     */
    @ParameterizedTest
    @CsvSource({"hostGame, true", "hostGame, false", "joinGame, true", "joinGame, false"})
    void aHostOrJoinCallTheAdapterRejectedFailsTheSession(
            final String method, final boolean errorAnswer) throws Exception {
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        iceConn.setupCallFail(
                method,
                errorAnswer ? new IceRpcException(-32000, "refused") : new TimeoutException());
        MockClientLifecycle lifecycle = launchedLifecycle(iceConn);
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().sessionFailed());

        lifecycle.post(roleFrame(method));

        assertTrue(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "the verdict must be recorded before TERMINATED commits");
        String action = "hostGame".equals(method) ? "host the game" : "join the game";
        assertEquals(
                1,
                warnings().stream().filter(w -> w.startsWith("Could not " + action)).count(),
                "one WARN must name the call: " + warnings());
    }

    /**
     * A call that failed because the adapter's connection closed records nothing itself (#445):
     * teardown's check decides the adapter (#438, #452), and this stand-in reports no disconnect,
     * so the check records nothing either. What this pins is the call's own rule: the session still
     * ends, and no WARN comes without a verdict.
     *
     * @param method the call whose connection closes under it
     */
    @ParameterizedTest
    @ValueSource(strings = {"hostGame", "joinGame"})
    void aHostOrJoinCallWhoseConnectionClosedLeavesItToTheAdapter(final String method)
            throws Exception {
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        iceConn.setupCallFail(
                method, new IOException("ICE adapter connection closed (REMOTE_CLOSE)"));
        MockClientLifecycle lifecycle = launchedLifecycle(iceConn);
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().sessionFailed());

        lifecycle.post(roleFrame(method));

        assertFalse(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "a closed connection is the adapter's finding, not the call's");
        assertEquals(ClientState.TERMINATED, lifecycle.getState(), "the session still ends");
        // Only the call's own line, at INFO. The closed connection is teardown's adapter check to
        // judge (#438, #452), and this stand-in's adapter is alive with its link intact.
        assertTrue(
                warnings().stream().noneMatch(w -> w.startsWith("Could not")),
                "the failed call must not warn without a verdict: " + warnings());
    }

    /**
     * A {@code HostGame} or {@code JoinGame} frame the client cannot read fails the session with a
     * verdict (#445), and one WARN names the frame. A number where the map, or the host's login,
     * belongs: the shape #445 reproduced against the real adapter.
     *
     * @param command the frame that arrives malformed
     */
    @ParameterizedTest
    @ValueSource(strings = {"HostGame", "JoinGame"})
    void aHostOrJoinFrameTheClientCannotReadFailsTheSession(final String command) throws Exception {
        MockClientLifecycle lifecycle =
                launchedLifecycle(
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()));
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().sessionFailed());
        ObjectNode frame = MAPPER.createObjectNode().put("command", command).put("target", "game");
        frame.set("args", MAPPER.createArrayNode().add(42));

        lifecycle.post("HostGame".equals(command) ? new HostGame(frame) : new JoinGame(frame));

        assertTrue(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "the verdict must be recorded before TERMINATED commits");
        assertEquals(
                1,
                warnings().stream().filter(w -> w.contains(command + " message")).count(),
                "one WARN must name the frame: " + warnings());
    }

    /**
     * An unchecked throw during the launch, once the adapter has started, ends the session instead
     * of stranding it (WBS-3.1.3.3-fix, #439). Left to {@code Transition}, it was contained and the
     * FSM stayed in IDLE with the adapter running and nothing to move it on, so this test would
     * time out. Now it is a failed launch, TERMINATED's teardown reaps the adapter, and the trace
     * is still logged at ERROR. {@code setIceServers} is the throw's site because it runs after the
     * adapter has started and connected.
     */
    @Test
    void anUncheckedThrowDuringTheLaunchEndsTheSessionInsteadOfStrandingIt() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        iceConn.setupCallThrow("setIceServers", new IllegalStateException("boom"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        iceConn,
                        gameLauncher,
                        iceLauncher,
                        new SessionTeardown(lobby));
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().launchFailed());

        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertTrue(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "the throw must end the session as a failed launch, not leave it in IDLE");
        // A bounded wait rather than an isAlive() probe: the wait failing is the assertion that
        // teardown reaped the adapter the launch had started.
        iceLauncher.getSubprocess().onExit().get(5, TimeUnit.SECONDS);
        assertTrue(
                errorsWithTrace().stream()
                        .anyMatch(m -> m.startsWith("Could not launch the game session")),
                "the defect must be logged at ERROR with its trace: " + errorsWithTrace());
    }

    /**
     * An unchecked throw in {@code hostGame} or {@code joinGame} ends the session too (#439).
     * Contained, it left the FSM in STARTING_GAME with the adapter up and the game waiting in LOBBY
     * for a role that never comes. The adapter and game did come up, so it is #445's verdict.
     *
     * @param method the call that throws
     */
    @ParameterizedTest
    @ValueSource(strings = {"hostGame", "joinGame"})
    void anUncheckedThrowInHostOrJoinEndsTheSession(final String method) throws Exception {
        DummyIceAdapterConnection iceConn =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        iceConn.setupCallThrow(method, new IllegalStateException("boom"));
        MockClientLifecycle lifecycle = launchedLifecycle(iceConn);
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().sessionFailed());

        lifecycle.post(roleFrame(method));

        assertTrue(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "the throw must end the session with a verdict, not leave it in STARTING_GAME");
        String action = "hostGame".equals(method) ? "host the game" : "join the game";
        assertTrue(
                errorsWithTrace().stream().anyMatch(m -> m.startsWith("Could not " + action)),
                "the defect must be logged at ERROR with its trace: " + errorsWithTrace());
        // Both came up for this one, so teardown must reap both. Bounded waits rather than
        // isAlive() probes: failing to complete is the assertion that nothing was left running.
        gameLaunchers
                .get(gameLaunchers.size() - 1)
                .getSubprocess()
                .onExit()
                .get(5, TimeUnit.SECONDS);
        iceLaunchers.get(iceLaunchers.size() - 1).getSubprocess().onExit().get(5, TimeUnit.SECONDS);
    }

    /**
     * A {@code game_launch} the client cannot use fails the launch (WBS-3.1.1.6-fix, #457), from
     * IDLE, where a host or a joiner waits for it, and from SEARCHING, where a matched game's
     * arrives. It used to be dropped with a WARN, leaving the run waiting for a launch it had
     * already received. The frame comes from the lobby through the real connection and handler, so
     * the rejection is handled on the connection's thread, where one WARN must name it. A value
     * Jackson would coerce is refused the same way (#474).
     *
     * @param route what the frame gets wrong, for the report
     * @param searching whether a search is on when the frame arrives
     * @param frame the {@code game_launch} frame
     * @param reason how the reason for refusing it begins
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableLaunches")
    void aGameLaunchTheClientCannotUseFailsTheLaunch(
            final String route, final boolean searching, final String frame, final String reason)
            throws Exception {
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
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        if (searching) {
            lifecycle.post(new SearchStarted(MAPPER.createObjectNode().put("state", "start")));
        }
        AtomicReference<String> committedOn = new AtomicReference<>();
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(
                                reached -> {
                                    committedOn.set(Thread.currentThread().getName());
                                    return lifecycle.verdicts().launchFailed();
                                });

        server.broadcastText(frame);

        assertTrue(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "the rejection must be a failed launch before TERMINATED commits");
        List<String> warnings = warningsOn(committedOn.get());
        assertEquals(1, warnings.size(), "one WARN must name the reason: " + warnings);
        assertTrue(
                warnings.get(0).startsWith("Could not read the game_launch frame (" + reason),
                warnings.get(0));
        assertFalse(iceLauncher.subprocessStarted(), "nothing may be launched");
        assertFalse(gameLauncher.subprocessStarted(), "nothing may be launched");
    }

    static Stream<Arguments> unusableLaunches() {
        return Stream.of(
                Arguments.of(
                        "IDLE, a value the validator refuses",
                        false,
                        "{\"command\":\"game_launch\",\"uid\":42,\"mod\":\"faf\",\"name\":\"x\","
                                + "\"game_type\":\"custom\",\"rating_type\":\"global\","
                                + "\"args\":[\"--danger\"]}",
                        "game_launch.args contains disallowed leading '-': --danger"),
                Arguments.of(
                        "IDLE, a frame that fails to decode",
                        false,
                        "{\"command\":\"game_launch\",\"uid\":\"abc\",\"mod\":\"faf\","
                                + "\"name\":\"x\",\"game_type\":\"custom\","
                                + "\"rating_type\":\"global\"}",
                        "game_launch.uid: Cannot coerce String value (\"abc\") to"
                                + " `java.lang.Integer` value)"),
                Arguments.of(
                        "IDLE, a value Jackson would coerce",
                        false,
                        "{\"command\":\"game_launch\",\"uid\":1.5,\"mod\":\"faf\","
                                + "\"name\":\"x\",\"game_type\":\"custom\","
                                + "\"rating_type\":\"global\"}",
                        "game_launch.uid: Cannot coerce Floating-point value (1.5) to"
                                + " `java.lang.Integer` value)"),
                Arguments.of(
                        "SEARCHING, a matched game with no map",
                        true,
                        "{\"command\":\"game_launch\",\"uid\":502,\"mod\":\"ladder1v1\","
                                + "\"name\":\"ladder1 Vs ladder2\",\"init_mode\":1,"
                                + "\"game_type\":\"matchmaker\",\"rating_type\":\"ladder_1v1\","
                                + "\"team\":2,\"faction\":1,\"expected_players\":2,"
                                + "\"map_position\":1}",
                        "game_launch.mapname invalid for matchmaker: null"),
                Arguments.of(
                        "SEARCHING, a matched game that fails to decode",
                        true,
                        "{\"command\":\"game_launch\",\"uid\":502,\"mod\":\"ladder1v1\","
                                + "\"name\":\"ladder1 Vs ladder2\",\"game_type\":\"matchmaker\","
                                + "\"rating_type\":\"ladder_1v1\",\"team\":\"two\"}",
                        "game_launch.team: Cannot coerce String value (\"two\") to"
                                + " `java.lang.Integer` value)"));
    }

    /**
     * A rejected {@code game_launch} records nothing once teardown has started (#457), the rule
     * every verdict shares: a signal's teardown can close the lobby while the frame is handled.
     * This teardown closes a lobby the lifecycle does not listen to, so the session is still in
     * IDLE when the rejection arrives, as it is until a signal's close comes back.
     */
    @Test
    void aGameLaunchRejectedOnceTeardownHasStartedRecordsNothing() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        SessionTeardown teardown =
                new SessionTeardown(new LobbyConnection(URI.create("ws://127.0.0.1:1")));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        new DummyGameLauncher(MINIMAL_CONFIG),
                        new DummyIceLauncher(MINIMAL_CONFIG),
                        teardown);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        teardown.run();
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.verdicts().launchFailed());

        lifecycle.post(new LaunchRejected("game_launch.uid invalid: -1"));

        assertFalse(
                failedAtCommit.get(5, TimeUnit.SECONDS),
                "nothing may be recorded once teardown has started");
        assertEquals(List.of(), warnings(), "its line drops to DEBUG");
    }

    /**
     * A lifecycle that has launched on {@code iceConn} and waits in STARTING_GAME for its role.
     *
     * @param iceConn the adapter connection, rigged by the caller
     * @return the lifecycle, in STARTING_GAME
     */
    private MockClientLifecycle launchedLifecycle(final DummyIceAdapterConnection iceConn) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
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
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
        return lifecycle;
    }

    /**
     * The lobby frame that makes the lifecycle issue {@code method}.
     *
     * @param method {@code hostGame} or {@code joinGame}
     * @return the matching role event
     */
    private static Event roleFrame(final String method) {
        return "hostGame".equals(method)
                ? new HostGame(HOST_GAME_MESSAGE)
                : new JoinGame(JOIN_GAME_MESSAGE);
    }

    /**
     * The ERROR lines the lifecycle logged on this test's thread with a stack trace attached; see
     * {@link #warnings()} for why only this thread's.
     *
     * @return the messages, in order
     */
    private List<String> errorsWithTrace() {
        String testThread = Thread.currentThread().getName();
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getThrowableProxy() != null)
                .filter(e -> testThread.equals(e.getThreadName()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * The WARN lines the lifecycle logged on this test's thread. Every failure here is decided
     * synchronously inside {@code post}, and a lifecycle an earlier class left behind can log on
     * another thread at any moment.
     *
     * @return the WARN messages, in order
     */
    private List<String> warnings() {
        return warningsOn(Thread.currentThread().getName());
    }

    /**
     * The WARN lines the lifecycle logged on one thread: the lobby connection's, for a failure a
     * frame from the server caused. See {@link #warnings()} for why only one thread's.
     *
     * @param thread the thread's name
     * @return the WARN messages, in order
     */
    private List<String> warningsOn(final String thread) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> thread.equals(e.getThreadName()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
