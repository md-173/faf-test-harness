package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectEvent;
import com.faforever.testharness.client.lobby.LobbyConnection.DisconnectReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link LobbySession} — the connect → authenticate → welcome → idle orchestration —
 * running against {@link ScriptedWebSocketServer}. No live lobby; the scripted server stands in for
 * the FAF lobby's handshake replies.
 */
@Timeout(20)
final class LobbySessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String WELCOME =
            "{\"command\":\"welcome\",\"me\":{\"id\":7,\"login\":\"MockPlayer\"},"
                    + "\"current_time\":\"2026-06-17T00:00:00Z\"}";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private LobbySession session;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // already closed
            }
        }
        server.stop(1000);
    }

    private static TokenSource fixedToken(final String jwt) {
        return () -> CompletableFuture.completedFuture(new AccessToken(jwt, Long.MAX_VALUE));
    }

    /**
     * Run {@code connectAndAuthenticate} on a worker thread while this thread drives the scripted
     * server through the {@code ask_session → session → auth → welcome} exchange.
     */
    private SessionState authenticate() throws Exception {
        lobby = new LobbyConnection(server.uri());
        session = new LobbySession(lobby, "uid-fixture", "1.2.3", "mock-agent");

        CompletableFuture<SessionState> result =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return session.connectAndAuthenticate(
                                        fixedToken("jwt-abc"), CONNECT_TIMEOUT, HANDSHAKE_TIMEOUT);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        });

        JsonNode ask = MAPPER.readTree(server.pollReceived(5, TimeUnit.SECONDS));
        assertEquals("ask_session", ask.get("command").asText());
        assertEquals("1.2.3", ask.get("version").asText());
        assertEquals("mock-agent", ask.get("user_agent").asText());
        server.broadcastText("{\"command\":\"session\",\"session\":42}");

        JsonNode auth = MAPPER.readTree(server.pollReceived(5, TimeUnit.SECONDS));
        assertEquals("auth", auth.get("command").asText());
        assertEquals("jwt-abc", auth.get("token").asText());
        server.broadcastText(WELCOME);

        return result.get(5, TimeUnit.SECONDS);
    }

    @Test
    void connectAndAuthenticateHydratesWelcome() throws Exception {
        SessionState state = authenticate();

        assertEquals(7, state.id());
        assertEquals("MockPlayer", state.login());
        assertTrue(session.sessionState().isPresent());
        assertEquals(7, session.sessionState().orElseThrow().id());
    }

    @Test
    void idleAutoPongsServerPing() throws Exception {
        authenticate();

        // While idle, a server ping must draw an automatic pong from the transport.
        server.broadcastText("{\"command\":\"ping\"}");
        JsonNode pong = MAPPER.readTree(server.pollReceived(5, TimeUnit.SECONDS));
        assertEquals("pong", pong.get("command").asText());
    }

    @Test
    void serverCloseReleasesAwaitDisconnect() throws Exception {
        authenticate();

        server.closeAllClean(1000, "bye");
        LobbyConnection.DisconnectEvent event = session.awaitDisconnect();

        assertEquals(LobbyConnection.DisconnectReason.CLEAN_CLOSE, event.reason());
    }

    @Test
    void localCloseReleasesAwaitDisconnect() throws Exception {
        authenticate();

        session.close().get(5, TimeUnit.SECONDS);
        LobbyConnection.DisconnectEvent event = session.awaitDisconnect();

        assertEquals(LobbyConnection.DisconnectReason.LOCAL_CLOSE, event.reason());
    }

    @Test
    void authenticationFailedPropagates() throws Exception {
        lobby = new LobbyConnection(server.uri());
        session = new LobbySession(lobby, "uid-fixture", "1.2.3", "mock-agent");

        CompletableFuture<SessionState> result =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return session.connectAndAuthenticate(
                                        fixedToken("jwt-abc"), CONNECT_TIMEOUT, HANDSHAKE_TIMEOUT);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        });

        server.pollReceived(5, TimeUnit.SECONDS); // ask_session
        server.broadcastText("{\"command\":\"session\",\"session\":42}");
        server.pollReceived(5, TimeUnit.SECONDS); // auth
        server.broadcastText(
                "{\"command\":\"authentication_failed\",\"text\":\"Login not found\"}");

        ExecutionException outer =
                assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        // supplyAsync wraps connectAndAuthenticate's ExecutionException in a CompletionException.
        Throwable handshakeFailure = outer.getCause().getCause();
        assertInstanceOf(AuthenticationException.class, handshakeFailure);
        assertEquals("Login not found", handshakeFailure.getMessage());
    }

    /**
     * A lobby that ends the login before {@code welcome} fails the start at once, by the time
     * {@code awaitDisconnect} returns, naming how the connection ended (#473): with a Close frame,
     * as faf-server ends a login it refuses, or by dropping it. The start used to stay pending
     * until its caller's timeout. The Close frame follows a frame, as faf-server's {@code notice}
     * or {@code invalid} does, so it is never the lone reply the fixture can strand. The drop
     * follows none: it queues no write to strand, and the JDK can miss a drop that lands right
     * behind a frame, with the client's socket left in CLOSE-WAIT (#485), which is not what this
     * tests.
     *
     * @param ending how the lobby ends the connection, for the report
     * @param dropped whether it drops the connection rather than closing it
     * @param failure how the start's failure begins
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "a Close frame, as faf-server sends | false | the lobby closed the connection"
                        + " before welcome (code 1000)",
                "a dropped connection | true | the lobby connection dropped before welcome"
            })
    void aLobbyThatEndsTheLoginFailsTheStart(
            final String ending, final boolean dropped, final String failure) throws Exception {
        lobby = new LobbyConnection(server.uri());
        session = new LobbySession(lobby, "uid-fixture", "1.2.3", "mock-agent");
        CompletableFuture<SessionState> start = session.start(fixedToken("jwt-abc"));
        server.pollReceived(5, TimeUnit.SECONDS); // ask_session

        if (dropped) {
            server.abruptlyTerminate();
        } else {
            server.broadcastText("{\"command\":\"notice\",\"style\":\"info\",\"text\":\"hello\"}");
            server.closeAllClean(1000, "");
        }

        session.awaitDisconnect();
        assertTrue(start.isCompletedExceptionally(), "the disconnect must settle the start first");
        ExecutionException e = assertThrows(ExecutionException.class, start::get);
        assertInstanceOf(AuthenticationException.class, e.getCause());
        assertTrue(e.getCause().getMessage().startsWith(failure), e.getCause().getMessage());
    }

    /**
     * How each disconnect before {@code welcome} reads (#473), including a drop the JDK reports as
     * an error, which the fixture cannot produce: its drops arrive as a close with code 1006.
     */
    @Test
    void aDisconnectBeforeWelcomeNamesHowTheConnectionEnded() {
        IOException reset = new IOException("Connection reset");

        assertEquals(
                "the lobby closed the connection before welcome (code 1000)",
                failure(new DisconnectEvent(DisconnectReason.CLEAN_CLOSE, 1000, "", null))
                        .getMessage());
        assertEquals(
                "the lobby closed the connection before welcome (code 4000, reason 'bye')",
                failure(new DisconnectEvent(DisconnectReason.CLEAN_CLOSE, 4000, "bye", null))
                        .getMessage());
        assertEquals(
                "the lobby connection dropped before welcome (code 1006, no close frame)",
                failure(new DisconnectEvent(DisconnectReason.ABRUPT_CLOSE, 1006, "", null))
                        .getMessage());
        Throwable dropped =
                failure(new DisconnectEvent(DisconnectReason.ABRUPT_CLOSE, 0, null, reset));
        assertEquals("the lobby connection dropped before welcome", dropped.getMessage());
        assertSame(reset, dropped.getCause());
        assertFalse(
                LobbySession.closedBeforeWelcome(
                                new DisconnectEvent(DisconnectReason.LOCAL_CLOSE, 1000, "", null))
                        .isDone(),
                "our own close must fail nothing");
        assertFalse(
                LobbySession.closedBeforeWelcome(
                                new DisconnectEvent(
                                        DisconnectReason.CONNECT_FAILED, 0, null, reset))
                        .isDone(),
                "a failed connect fails the start with its own cause");
    }

    /**
     * The session's own close before {@code welcome} leaves the start pending (#473): before {@code
     * welcome} that close is a signal's, which must not read as a failure. The disconnect settles
     * the start before it releases {@code awaitDisconnect}, so this reads it straight after, with
     * no wait.
     */
    @Test
    void ourOwnCloseBeforeWelcomeLeavesTheStartPending() throws Exception {
        lobby = new LobbyConnection(server.uri());
        session = new LobbySession(lobby, "uid-fixture", "1.2.3", "mock-agent");
        CompletableFuture<SessionState> start = session.start(fixedToken("jwt-abc"));
        server.pollReceived(5, TimeUnit.SECONDS); // ask_session

        session.close();

        assertEquals(DisconnectReason.LOCAL_CLOSE, session.awaitDisconnect().reason());
        assertFalse(start.isDone(), "our own close must not settle the start");
    }

    /**
     * Each {@code notice} is logged with its text on one line (#473): at WARN for an error, which
     * faf-server sends before ending a login it refuses, a warning, a kick and a kill, and at INFO
     * otherwise, the greeting faf-server gives an unofficial client included. The frames go ahead
     * of a close, which flushes them, and the close is handled after every frame before it, so the
     * log is complete once the disconnect is seen.
     */
    @Test
    void aNoticeIsLoggedWithItsTextOnOneLine() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(LobbySession.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        try {
            lobby = new LobbyConnection(server.uri());
            session = new LobbySession(lobby, "uid-fixture", "1.2.3", "mock-agent");
            session.start(fixedToken("jwt-abc"));
            server.pollReceived(5, TimeUnit.SECONDS); // ask_session

            server.broadcastText(
                    "{\"command\":\"notice\",\"style\":\"info\","
                            + "\"text\":\"You are using an unofficial client version!\"}");
            server.broadcastText(
                    "{\"command\":\"notice\",\"style\":\"error\","
                            + "\"text\":\"You are banned.\\n\\nReason: rig\"}");
            server.broadcastText("{\"command\":\"notice\",\"style\":\"kick\"}");
            server.broadcastText(
                    "{\"command\":\"notice\",\"style\":\"warning\",\"text\":\"mind\"}");
            server.broadcastText("{\"command\":\"notice\",\"style\":\"kill\"}");
            server.broadcastText("{\"command\":\"notice\",\"text\":\"no style\"}");
            server.closeAllClean(1000, "");
            session.awaitDisconnect();

            assertEquals(
                    List.of(
                            "INFO lobby notice (info): You are using an unofficial client version!",
                            "WARN lobby notice (error): You are banned. Reason: rig",
                            "WARN lobby notice (kick)",
                            "WARN lobby notice (warning): mind",
                            "WARN lobby notice (kill)",
                            "INFO lobby notice (info): no style"),
                    appender.list.stream()
                            .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                            .toList());
        } finally {
            appender.stop();
            logger.detachAppender(appender);
        }
    }

    private static Throwable failure(final DisconnectEvent event) {
        CompletableFuture<JsonNode> ended = LobbySession.closedBeforeWelcome(event);
        assertTrue(ended.isCompletedExceptionally(), "the lobby's ending must fail the start");
        Throwable failure = ended.exceptionNow();
        assertInstanceOf(AuthenticationException.class, failure);
        return failure;
    }
}
