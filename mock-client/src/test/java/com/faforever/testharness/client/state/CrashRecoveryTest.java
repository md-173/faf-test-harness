package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Client game crash recovery (#211): a game that dies before reaching PLAYING must not hang the
 * client. Covers the missing {@link GameExited} exit edges from STARTING_GAME, HOSTING, and
 * JOINING, the local WARN/INFO exit classification, the {@code GameState Ended} frame sent to the
 * lobby on every exit, and that the pre-existing clean-exit / late-{@link GameExited} behaviour
 * from #192/#193 is unchanged.
 */
final class CrashRecoveryTest {
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

    /**
     * A subprocess that hangs reading from its (never-written, never-closed) stdin pipe, so a test
     * can drive its exit explicitly (e.g. via {@link MockClientLifecycle#shutdown()}) instead of
     * racing a real one. See {@code GameEndReportingTest}'s identical fixture.
     */
    private static final ProcessBuilder HANGING_PROCESS = new ProcessBuilder("sort");

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private ChildGameLauncher gameLauncher;
    private DummyIceLauncher iceLauncher;
    private ListAppender<ILoggingEvent> appender;
    private Logger root;

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
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        appender.stop();
        root.detachAppender(appender);
        if (gameLauncher != null && gameLauncher.manager != null) {
            gameLauncher.manager.terminate(Duration.ofSeconds(1));
        }
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

    /** Cross-platform child process argv that exits with {@code code} and nothing else. */
    private static ProcessBuilder exitingWith(int code) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? new ProcessBuilder("cmd", "/c", "exit", String.valueOf(code))
                : new ProcessBuilder("sh", "-c", "exit " + code);
    }

    @Test
    void exitDuringHostingReachesTerminatedAndRunsTeardown() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(1));
        SessionTeardown teardown = new SessionTeardown(lobby);
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, teardown);

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        // Only teardown kills the ICE launcher's subprocess, and teardown completes before
        // stateReached(TERMINATED) resolves (the TERMINATED entry hook runs synchronously in
        // receiveEvent) — so a dead subprocess here proves teardown actually ran, unlike asserting
        // on the lobby socket, which would look closed either way (LobbyDisconnectPlayingTest:153
        // uses the same check).
        assertFalse(
                iceLauncher.getSubprocess().isAlive(),
                "teardown should already have killed the ICE adapter subprocess");
    }

    @Test
    void nonZeroExitProducesWarnLineWithExitCode() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(42));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        boolean warned =
                getLogSnapshot().stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage().contains("42"));
        assertTrue(warned, "a non-zero exit must be logged as WARN with the exit code");
    }

    @Test
    void cleanExitDuringHostingProducesInfoLine() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(0));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        boolean infoLogged =
                getLogSnapshot().stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.INFO
                                                && e.getFormattedMessage().contains("exit code"));
        assertTrue(infoLogged, "a zero exit must be logged as INFO");
    }

    @Test
    void gameStateEndedSentToLobbyOnCrash() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(1));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        String message = server.pollReceived(5, TimeUnit.SECONDS);
        JsonNode envelope = MAPPER.readTree(message);
        assertEquals("GameState", envelope.get("command").asText());
        assertEquals("game", envelope.get("target").asText());
        assertEquals("Ended", envelope.get("args").get(0).asText());
    }

    @Test
    void shutdownFromHostingProducesNoWarn() throws Exception {
        // A hanging process, not exitingWith(...): the exit here must be the harness's own
        // teardown-initiated SIGTERM, not the child's own quick exit, to exercise the
        // teardown.hasRun() branch instead of racing it.
        gameLauncher = new ChildGameLauncher(HANGING_PROCESS);
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.shutdown();
        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);

        List<ILoggingEvent> snap = getLogSnapshot();
        boolean falseCrashWarned =
                snap.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.WARN
                                                && e.getFormattedMessage().contains("mock-game"));
        assertFalse(
                falseCrashWarned,
                "a harness-initiated kill must not be logged as a crash. captured: " + snap);
    }

    @Test
    void fastExitDuringLaunchStillReachesTerminated() throws Exception {
        // Forces the game to have already exited before launchGame() returns, so gameExit
        // completes on the same (synchronized, still-inside-receiveEvent) thread that is
        // processing the LaunchGame transition — deterministically exercising the #211
        // thenAcceptAsync fix instead of leaving it to chance.
        ImmediatelyExitingGameLauncher launcher =
                new ImmediatelyExitingGameLauncher(exitingWith(1));
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        launcher,
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        lifecycle.stateReached(ClientState.TERMINATED).get(10, TimeUnit.SECONDS);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
    }

    @Test
    void lateGameExitedAfterTerminatedIsNoOp() throws Exception {
        gameLauncher = new ChildGameLauncher(exitingWith(1));
        MockClientLifecycle lifecycle = hostingLifecycle(gameLauncher, new SessionTeardown(lobby));

        lifecycle.stateReached(ClientState.TERMINATED).get(15, TimeUnit.SECONDS);
        assertEquals(ClientState.TERMINATED, lifecycle.getState());

        lifecycle.post(new GameExited(0));

        assertEquals(ClientState.TERMINATED, lifecycle.getState(), "must stay TERMINATED");
    }

    private MockClientLifecycle hostingLifecycle(
            ChildGameLauncher launcher, SessionTeardown teardown) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        launcher,
                        iceLauncher,
                        teardown);

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(hostGameMessage()));
        assertEquals(ClientState.HOSTING, lifecycle.getState());
        return lifecycle;
    }

    private static JsonNode hostGameMessage() {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        return node;
    }

    // Get a snapshot of the log, which can be examined without ConcurrentModificationExceptions
    // occuring.
    private List<ILoggingEvent> getLogSnapshot() {
        List<ILoggingEvent> snap;
        synchronized (appender) {
            snap = List.copyOf(appender.list);
        }
        return snap;
    }

    /** Launches a real child from the given builder and retains the manager for assertions. */
    private final class ChildGameLauncher extends MockGameLauncher {
        private final ProcessBuilder builder;
        private SubprocessManager manager;

        ChildGameLauncher(ProcessBuilder builder) {
            super(MINIMAL_CONFIG);
            this.builder = builder;
        }

        @Override
        public SubprocessManager start(LaunchIdentity identity) throws MockGameLaunchException {
            try {
                manager = SubprocessManager.start(builder, "TEST GAME", Duration.ofSeconds(5));
                return manager;
            } catch (IOException e) {
                throw new MockGameLaunchException(e.getMessage());
            }
        }
    }

    /**
     * Like {@link ChildGameLauncher}, but blocks inside {@link #start()} until the child has
     * actually exited before returning its manager — guaranteeing {@code gameExit} completes within
     * {@code launchGame()}'s own call stack instead of racing it.
     */
    private final class ImmediatelyExitingGameLauncher extends MockGameLauncher {
        private final ProcessBuilder builder;

        ImmediatelyExitingGameLauncher(ProcessBuilder builder) {
            super(MINIMAL_CONFIG);
            this.builder = builder;
        }

        @Override
        public SubprocessManager start(LaunchIdentity identity) throws MockGameLaunchException {
            try {
                SubprocessManager manager =
                        SubprocessManager.start(builder, "TEST GAME", Duration.ofSeconds(5));
                manager.onExit().get(5, TimeUnit.SECONDS);
                return manager;
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
                throw new MockGameLaunchException(e.getMessage());
            }
        }
    }
}
