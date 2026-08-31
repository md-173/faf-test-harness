package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.GameQueueConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.GameMatchmakingSender;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
                    Optional.empty());

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
    }

    @AfterEach
    void tearDown() throws Exception {
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
                queueConfig);
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

        var searchingAgain = lifecycle.stateReached(ClientState.SEARCHING);
        new GameMatchmakingSender(lobby).sendStart(queueConfig);
        server.pollReceived(3, TimeUnit.SECONDS);
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

        var searchingAgain = lifecycle.stateReached(ClientState.SEARCHING);
        new GameMatchmakingSender(lobby).sendStart(queueConfig);
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

    @Test
    void searchTimeoutInResponseToStartProducesNoStateChangeAndLogsExpiry() throws Exception {
        MockClientLifecycle lifecycle =
                newLifecycle(Optional.of(new GameQueueConfig("ladder1v1", Optional.empty())));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        server.pollReceived(3, TimeUnit.SECONDS); // the outbound game_matchmaking start

        server.broadcastText(
                "{\"command\":\"search_timeout\",\"timeouts\":"
                        + "[{\"player\":\"Rhiza\",\"expires_at\":\"2026-01-01T00:00:00Z\"}]}");
        // No FSM event follows a search_timeout; give the handler a moment to run and assert
        // the state never moved off IDLE.
        Thread.sleep(200);
        assertEquals(ClientState.IDLE, lifecycle.getState());
        assertTrue(
                messages().stream()
                        .anyMatch(m -> m.contains("search_timeout") && m.contains("Rhiza")),
                "the expiry must be logged: " + messages());
        assertTrue(
                messages().stream().noneMatch(m -> m.equals("state entry: SEARCHING")),
                "no SEARCHING entry may occur on a search_timeout");
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

    @Test
    void idleSendsNothingWhenNoQueueConfigured() throws Exception {
        MockClientLifecycle lifecycle = newLifecycle(Optional.empty());

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        assertEquals(ClientState.IDLE, lifecycle.getState());

        assertThrows(AssertionError.class, () -> server.pollReceived(500, TimeUnit.MILLISECONDS));
    }
}
