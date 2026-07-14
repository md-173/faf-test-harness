package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link LobbyHandshake} running against {@link ScriptedWebSocketServer}. */
final class LobbyHandshakeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;

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

    @Test
    void handshakeCompletesOnWelcome() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        CompletableFuture<JsonNode> welcome = handshake.perform(fixedToken("jwt-token-abc"));

        // 1. Client sends ask_session.
        JsonNode ask = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("ask_session", ask.get("command").asText());
        assertEquals("1.0.0", ask.get("version").asText());
        assertEquals("mock-client-test", ask.get("user_agent").asText());

        // 2. Server replies with session.
        server.broadcastText("{\"command\":\"session\",\"session\":42}");

        // 3. Client sends auth carrying the JWT + unique_id + session.
        JsonNode auth = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("auth", auth.get("command").asText());
        assertEquals("jwt-token-abc", auth.get("token").asText());
        assertEquals("uid-fixture", auth.get("unique_id").asText());
        assertEquals(42L, auth.get("session").asLong());

        // 4. Server sends welcome.
        server.broadcastText("{\"command\":\"welcome\",\"id\":3,\"login\":\"Rhiza\"}");

        JsonNode result = welcome.get(2, TimeUnit.SECONDS);
        assertEquals("welcome", result.get("command").asText());
        assertEquals("Rhiza", result.get("login").asText());
    }

    @Test
    void handshakeFailsOnAuthenticationFailed() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        CompletableFuture<JsonNode> welcome = handshake.perform(fixedToken("jwt-token-abc"));

        // Drain ask_session, reply with session.
        server.pollReceived(2, TimeUnit.SECONDS);
        server.broadcastText("{\"command\":\"session\",\"session\":42}");

        // Drain auth, reply with authentication_failed.
        server.pollReceived(2, TimeUnit.SECONDS);
        server.broadcastText(
                "{\"command\":\"authentication_failed\",\"text\":\"Login not found\"}");

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> welcome.get(2, TimeUnit.SECONDS));
        assertEquals(AuthenticationException.class, e.getCause().getClass());
        assertEquals("Login not found", e.getCause().getMessage());
    }

    @Test
    void handshakeFailsWhenTokenSourceThrows() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        TokenSource failing =
                () -> CompletableFuture.failedFuture(new AuthenticationException("bad creds"));

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        CompletableFuture<JsonNode> welcome = handshake.perform(failing);

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> welcome.get(2, TimeUnit.SECONDS));
        assertEquals(AuthenticationException.class, e.getCause().getClass());
        assertEquals("bad creds", e.getCause().getMessage());
    }

    @Test
    void doublePerformIsRejected() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        handshake.perform(fixedToken("first"));
        assertThrows(IllegalStateException.class, () -> handshake.perform(fixedToken("second")));
    }

    @Test
    void handshakeFailsOnMalformedSession() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        CompletableFuture<JsonNode> welcome = handshake.perform(fixedToken("jwt-token-abc"));

        // Drain ask_session, then reply with a session frame missing the 'session' field — the
        // handshake must fail cleanly rather than NPE on the listener thread and hang.
        server.pollReceived(2, TimeUnit.SECONDS);
        server.broadcastText("{\"command\":\"session\"}");

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> welcome.get(2, TimeUnit.SECONDS));
        assertEquals(AuthenticationException.class, e.getCause().getClass());
    }

    @Test
    void uidBinaryOutputBecomesUniqueId(@TempDir final Path dir) throws Exception {
        assumeTrue(
                !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
                "POSIX-only: uses a shell script as a stand-in faf-uid binary");
        // A stand-in 'faf-uid' that echoes a session-derived token, proving the handshake runs the
        // binary with the lobby session and sends its stdout as unique_id.
        Path fakeUid = dir.resolve("fake-uid.sh");
        Files.writeString(fakeUid, "#!/bin/sh\necho \"UID-FOR-$1\"\n");
        assertTrueExecutable(fakeUid);

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "static-uid", "1.0.0", "ua", Optional.of(fakeUid));
        handshake.perform(fixedToken("jwt-token-abc"));

        server.pollReceived(2, TimeUnit.SECONDS); // ask_session
        server.broadcastText("{\"command\":\"session\",\"session\":99}");

        JsonNode auth = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("auth", auth.get("command").asText());
        assertEquals("UID-FOR-99", auth.get("unique_id").asText());
    }

    @Test
    void failingUidBinaryFallsBackToStaticUniqueId(@TempDir final Path dir) throws Exception {
        assumeTrue(
                !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
                "POSIX-only: uses a shell script as a stand-in faf-uid binary");
        // A stand-in 'faf-uid' that writes an error to stderr and exits non-zero, proving the
        // handshake falls back to the static unique_id when the tool fails after starting.
        Path fakeUid = dir.resolve("failing-uid.sh");
        Files.writeString(fakeUid, "#!/bin/sh\necho 'boom: no hardware id' >&2\nexit 3\n");
        assertTrueExecutable(fakeUid);

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "static-uid", "1.0.0", "ua", Optional.of(fakeUid));
        handshake.perform(fixedToken("jwt-token-abc"));

        server.pollReceived(2, TimeUnit.SECONDS); // ask_session
        server.broadcastText("{\"command\":\"session\",\"session\":13}");

        JsonNode auth = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("auth", auth.get("command").asText());
        assertEquals("static-uid", auth.get("unique_id").asText());
    }

    @Test
    void missingUidBinaryFallsBackToStaticUniqueId() throws Exception {
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        Path absent = Path.of("nonexistent", "faf-uid-does-not-exist");
        LobbyHandshake handshake =
                new LobbyHandshake(lobby, "static-uid", "1.0.0", "ua", Optional.of(absent));
        handshake.perform(fixedToken("jwt-token-abc"));

        server.pollReceived(2, TimeUnit.SECONDS); // ask_session
        server.broadcastText("{\"command\":\"session\",\"session\":7}");

        JsonNode auth = MAPPER.readTree(server.pollReceived(2, TimeUnit.SECONDS));
        assertEquals("auth", auth.get("command").asText());
        assertEquals("static-uid", auth.get("unique_id").asText());
    }

    private static void assertTrueExecutable(final Path file) {
        if (!file.toFile().setExecutable(true)) {
            throw new AssertionError("could not mark stand-in faf-uid script executable: " + file);
        }
    }
}
