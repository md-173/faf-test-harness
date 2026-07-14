package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
        assertTrue(session.isDisconnected());
    }

    @Test
    void localCloseReleasesAwaitDisconnect() throws Exception {
        authenticate();

        session.close().get(5, TimeUnit.SECONDS);
        LobbyConnection.DisconnectEvent event = session.awaitDisconnect();

        assertEquals(LobbyConnection.DisconnectReason.LOCAL_CLOSE, event.reason());
        assertTrue(session.isDisconnected());
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
}
