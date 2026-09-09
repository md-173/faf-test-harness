package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.GameQueueConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.statemachine.StateMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Covers the client matchmaking queue (#224): from IDLE, the FSM sends {@code game_matchmaking}
 * start for a configured queue (lobby-protocol-spec.md §4.3 / §10.2), reaches SEARCHING on the
 * server's {@code search_info} confirmation, and returns to IDLE on either a stop confirmation or a
 * {@code match_cancelled}. There is no accept/decline step in the protocol — a {@code match_found}
 * proceeds straight to the existing {@code game_launch} launch path, which this only drives up to
 * the SEARCHING to STARTING_GAME edge; the launch itself is covered elsewhere (R24/#218).
 */
final class MatchmakingQueueTest {
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
                    Optional.empty(),
                    0);

    private static final GameConfig MINIMAL_GAME_CONFIG =
            new GameConfig(
                    12345,
                    "faf",
                    "Test Game Name",
                    0,
                    "matchmaker",
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
    private ListAppender<ILoggingEvent> appender;
    private Logger lifecycleLogger;
    private ListAppender<ILoggingEvent> machineAppender;
    private Logger machineLogger;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new ListAppender<>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        lifecycleLogger = context.getLogger(MockClientLifecycle.class);
        lifecycleLogger.addAppender(appender);

        // Separate capture on the framework's own logger: the unregistered-event WARN that the
        // IDLE SearchStopped self-loop exists to prevent is emitted by StateMachine, not by
        // MockClientLifecycle, so it never reaches the appender above.
        machineAppender = new ListAppender<>();
        machineAppender.list = new CopyOnWriteArrayList<>();
        machineAppender.setContext(context);
        machineAppender.start();
        machineLogger = context.getLogger(StateMachine.class);
        machineLogger.addAppender(machineAppender);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (machineAppender != null) {
            machineAppender.stop();
            machineLogger.detachAppender(machineAppender);
        }
        if (appender != null) {
            appender.stop();
            lifecycleLogger.detachAppender(appender);
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        server.stop(1000);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The {@link StateMachine} framework's own log records, for the unregistered-event WARN. */
    private List<String> machineMessages() {
        return machineAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Waits for a lifecycle log record matching {@code predicate}. Send failures surface on the
     * future the sender returns, so the record can land slightly after the call that triggered it;
     * polling avoids the fixed sleep that would make this timing-dependent.
     *
     * @param predicate matcher for the formatted message.
     * @param timeoutMillis how long to wait before giving up.
     * @return {@code true} if a matching record arrived in time.
     */
    private boolean awaitMessage(Predicate<String> predicate, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (messages().stream().anyMatch(predicate)) {
                return true;
            }
            Thread.sleep(20);
        }
        return messages().stream().anyMatch(predicate);
    }

    /** Copies {@link #MINIMAL_CONFIG} with {@code queueConfig} overridden. */
    private static MockClientConfig configWithQueueConfig(Optional<GameQueueConfig> queueConfig) {
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
                MINIMAL_CONFIG.hostConfig(),
                MINIMAL_CONFIG.joinConfig(),
                queueConfig,
                MINIMAL_CONFIG.iceRelayDelayMs());
    }

    private MockClientLifecycle newLifecycle(Optional<GameQueueConfig> queueConfig)
            throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        MockClientConfig config = configWithQueueConfig(queueConfig);
        return new MockClientLifecycle(
                config,
                session,
                new DummyIceAdapterConnection(config.iceAdapterRpcPort()),
                new DummyGameLauncher(config),
                new DummyIceLauncher(config),
                new SessionTeardown(lobby));
    }

    private static String searchInfo(String queueName, String state) {
        return "{\"command\":\"search_info\",\"queue_name\":\""
                + queueName
                + "\",\"state\":\""
                + state
                + "\"}";
    }

    @Test
    void startProducesOutboundMessageAndSearching() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameQueueConfig("ladder1v1", Optional.of(2))));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        JsonNode sent = MAPPER.readTree(server.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("game_matchmaking", sent.get("command").asText());
        assertEquals("ladder1v1", sent.get("queue_name").asText());
        assertEquals("start", sent.get("state").asText());
        assertEquals(2, sent.get("faction").asInt());

        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);
        assertEquals(ClientState.SEARCHING, lifecycle.getState());
        assertTrue(messages().contains("state entry: SEARCHING"));
    }

    @Test
    void stopReturnsToIdleAndANewSearchCanFollow() throws Exception {
        GameQueueConfig queueConfig = new GameQueueConfig("ladder1v1", Optional.empty());
        MockClientLifecycle lifecycle = newLifecycle(Optional.of(queueConfig));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS); // the initial game_matchmaking start
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);

        var idleAgain = lifecycle.stateReached(ClientState.IDLE);
        lifecycle.stopSearch();
        JsonNode stopSent = MAPPER.readTree(server.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("game_matchmaking", stopSent.get("command").asText());
        assertEquals("stop", stopSent.get("state").asText());
        assertTrue(
                !stopSent.has("faction") || stopSent.get("faction").isNull(),
                "faction must not be sent on a stop request");

        server.broadcastText(searchInfo("ladder1v1", "stop"));
        idleAgain.get(3, TimeUnit.SECONDS);

        // The stop must actually stick. Re-entering IDLE re-fires its entry hooks (Transition
        // runs to.entry() on every non-self-loop), so an ungated hook would re-send
        // game_matchmaking start here and make stopSearch() inert — the client would look unable
        // to leave the queue. Nothing may reach the wire until the explicit restart below.
        assertThrows(
                AssertionError.class,
                () -> server.pollReceived(500, TimeUnit.MILLISECONDS),
                "a confirmed stop must not be undone by an automatic re-queue");

        var searchingAgain = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.startSearch();
        JsonNode restart = MAPPER.readTree(server.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("game_matchmaking", restart.get("command").asText());
        assertEquals("start", restart.get("state").asText());
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searchingAgain.get(3, TimeUnit.SECONDS);
        assertEquals(ClientState.SEARCHING, lifecycle.getState());
    }

    @Test
    void matchCancelledReturnsToIdleAndANewSearchCanFollow() throws Exception {
        GameQueueConfig queueConfig = new GameQueueConfig("ladder1v1", Optional.empty());
        MockClientLifecycle lifecycle = newLifecycle(Optional.of(queueConfig));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS);
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);

        var idleAgain = lifecycle.stateReached(ClientState.IDLE);
        server.broadcastText("{\"command\":\"match_cancelled\",\"game_id\":null}");
        idleAgain.get(3, TimeUnit.SECONDS);
        assertEquals(ClientState.IDLE, lifecycle.getState());

        // A cancelled match must not silently re-queue. faf-server emits match_cancelled from the
        // handler that also calls violation_service.register_violations, so an automatic re-queue
        // would loop straight back into the path that earns a matchmaker time-out — and would bury
        // the cancellation, which is the finding a tester is here to see.
        assertThrows(
                AssertionError.class,
                () -> server.pollReceived(500, TimeUnit.MILLISECONDS),
                "match_cancelled must not trigger an automatic re-queue");

        var searchingAgain = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.startSearch();
        server.pollReceived(3, TimeUnit.SECONDS);
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searchingAgain.get(3, TimeUnit.SECONDS);
        assertEquals(ClientState.SEARCHING, lifecycle.getState());
    }

    @Test
    void matchFoundFollowedByGameLaunchEntersExistingLaunchPath() throws Exception {
        GameQueueConfig queueConfig = new GameQueueConfig("ladder1v1", Optional.empty());
        MockClientLifecycle lifecycle = newLifecycle(Optional.of(queueConfig));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS);
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);

        server.broadcastText("{\"command\":\"match_found\",\"queue_name\":\"ladder1v1\"}");
        // No accept/decline step and no FSM edge for match_found: the client stays SEARCHING.
        Thread.sleep(200);
        assertEquals(ClientState.SEARCHING, lifecycle.getState());
        assertTrue(messages().stream().anyMatch(m -> m.startsWith("match found: queue_name=")));

        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
    }

    /**
     * The wide path for {@code match_cancelled} is a failure <em>after</em> {@code game_launch}
     * (#304 review): {@code ladder_service.launch_match} writes the launch frame, then times out in
     * {@code game.wait_hosted(60)}, and {@code _start_game} answers by broadcasting {@code
     * match_cancelled}. Registered only on SEARCHING, that frame found no transition and the run
     * hung with a live adapter and game.
     */
    @Test
    void matchCancelledAfterGameLaunchTerminates() throws Exception {
        GameQueueConfig queueConfig = new GameQueueConfig("ladder1v1", Optional.empty());
        MockClientLifecycle lifecycle = newLifecycle(Optional.of(queueConfig));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS);
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);

        server.broadcastText("{\"command\":\"match_found\",\"queue_name\":\"ladder1v1\"}");
        var terminated = lifecycle.stateReached(ClientState.TERMINATED);
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());

        server.broadcastText("{\"command\":\"match_cancelled\",\"game_id\":12345}");
        terminated.get(3, TimeUnit.SECONDS);

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertTrue(
                messages().stream()
                        .anyMatch(
                                m ->
                                        m.startsWith("match_cancelled after game_launch")
                                                && m.contains("12345")),
                "the cancellation must be reported with its game id: " + messages());
        assertTrue(
                machineMessages().stream().noneMatch(m -> m.contains("MatchCancelled")),
                "match_cancelled must not hit the unregistered-event WARN: " + machineMessages());
    }

    /**
     * faf-server accepts {@code game_matchmaking} start from SEARCHING_LADDER as well as IDLE and
     * re-confirms each time, and the public {@link MockClientLifecycle#startSearch()} lets a
     * harness trigger it directly. Without the SEARCHING self-loop this produced the same
     * unregistered-event WARN the IDLE stop self-loop was added to prevent (#304 review).
     */
    @Test
    void searchInfoStartWhileSearchingCausesNoStateChangeOrWarning() throws Exception {
        GameQueueConfig queueConfig = new GameQueueConfig("ladder1v1", Optional.empty());
        MockClientLifecycle lifecycle = newLifecycle(Optional.of(queueConfig));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS);
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);

        // DEBUG so the self-loop's own line is captured: waiting for it proves the second frame was
        // actually processed. A bare sleep would let this pass vacuously if the frame never
        // arrived, which is the failure mode the stop/cancel tests originally had.
        Level previous = lifecycleLogger.getLevel();
        lifecycleLogger.setLevel(Level.DEBUG);
        try {
            server.broadcastText(searchInfo("ladder1v1", "start"));
            assertTrue(
                    awaitMessage(
                            m -> m.contains("search_info start received while already SEARCHING"),
                            3000),
                    "the repeated start confirmation must be handled: " + messages());
        } finally {
            lifecycleLogger.setLevel(previous);
        }

        assertEquals(ClientState.SEARCHING, lifecycle.getState());
        assertTrue(
                machineMessages().stream().noneMatch(m -> m.contains("SearchStarted")),
                "a repeated start confirmation must not warn: " + machineMessages());
        // The self-loop must not re-run SEARCHING's entry hooks, so no second state-entry line.
        assertEquals(
                1,
                messages().stream().filter(m -> m.equals("state entry: SEARCHING")).count(),
                "self-loop must not re-emit the state entry line: " + messages());
    }

    @Test
    void searchTimeoutInResponseToStartProducesNoStateChangeAndLogsExpiry() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameQueueConfig("ladder1v1", Optional.empty())));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS); // the outbound game_matchmaking start

        // The real refusal, as faf-server's ladder_service.start_search sends it: three frames,
        // not one. "player" is a numeric player id ({"player": p.id}), never a login, and the
        // search_timeout is always paired with a search_info stop for the search that never
        // started — plus a deprecated notice kept for older clients.
        server.broadcastText(
                "{\"command\":\"search_timeout\",\"timeouts\":"
                        + "[{\"player\":42,\"expires_at\":\"2026-01-01T00:00:00Z\"}]}");
        server.broadcastText(searchInfo("ladder1v1", "stop"));
        server.broadcastText(
                "{\"command\":\"notice\",\"style\":\"info\","
                        + "\"text\":\"Player Rhiza is timed out for 10 minutes\"}");
        // No FSM event follows a search_timeout; give the handlers a moment to run and assert
        // the state never moved off IDLE.
        Thread.sleep(200);
        assertEquals(ClientState.IDLE, lifecycle.getState());
        assertTrue(
                messages().stream()
                        .anyMatch(m -> m.contains("search_timeout") && m.contains("player_id=42")),
                "the expiry must be logged against the player id: " + messages());
        assertTrue(
                messages().stream().noneMatch(m -> m.equals("state entry: SEARCHING")),
                "no SEARCHING entry may occur on a search_timeout");
        // The paired stop is absorbed by the IDLE self-loop rather than surfacing as an
        // unregistered-event warning on what is an entirely ordinary refusal.
        assertTrue(
                machineMessages().stream()
                        .noneMatch(m -> m.contains("No matching transitions for SearchStopped")),
                "the paired search_info stop must not warn: " + machineMessages());
    }

    @Test
    void matchmakerInfoCausesNoStateChange() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameQueueConfig("ladder1v1", Optional.empty())));

        var searching = lifecycle.stateReached(ClientState.SEARCHING);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS);
        server.broadcastText(searchInfo("ladder1v1", "start"));
        searching.get(3, TimeUnit.SECONDS);

        server.broadcastText("{\"command\":\"matchmaker_info\",\"queues\":[]}");
        Thread.sleep(200);
        assertEquals(ClientState.SEARCHING, lifecycle.getState());
    }

    /**
     * {@code LobbyConnection.send} resets its internal chain to a non-failed stage so one bad send
     * cannot poison the next, which leaves the failure visible only on the future the caller gets
     * back. The senders discarded it, so a send that never reached the socket said nothing anywhere
     * (#304 review).
     */
    @Test
    void failedGameMatchmakingSendIsLogged() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameQueueConfig("ladder1v1", Optional.empty())));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS); // the initial game_matchmaking start

        // Closing the lobby also drives the FSM to TERMINATED, which runs teardown, and a send
        // failing after teardown is deliberately demoted to DEBUG as an expected shutdown artefact.
        // Capture both levels so this asserts "not silent" rather than one particular severity.
        Level previous = lifecycleLogger.getLevel();
        lifecycleLogger.setLevel(Level.DEBUG);
        try {
            lobby.close().get(2, TimeUnit.SECONDS);

            lifecycle.startSearch();

            assertTrue(
                    awaitMessage(m -> m.contains("game_matchmaking start send failed"), 3000),
                    "a failed send must not be silent: " + messages());
        } finally {
            lifecycleLogger.setLevel(previous);
        }
    }

    @Test
    void idleSendsNothingWhenNoQueueConfigured() throws Exception {
        MockClientLifecycle lifecycle = newLifecycle(Optional.empty());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        assertThrows(AssertionError.class, () -> server.pollReceived(500, TimeUnit.MILLISECONDS));
    }
}
