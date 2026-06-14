package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LobbyAuthenticator} running against an in-process {@link HttpServer} bound
 * to an OS-chosen port. The server is rescripted per-test via {@link #setHandler}.
 */
final class LobbyAuthenticatorTest {

    private static final String CLIENT_ID = "0001-0002-0003-0004";

    private HttpServer server;
    private URI tokenEndpoint;
    private Path tokenFile;

    private final ObjectMapper mapper = new ObjectMapper();

    // Last captured request — populated by {@link CapturingHandler}.
    private volatile String requestMethod;
    private volatile String requestBody;

    @BeforeEach
    void setupServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        server.start();
        tokenEndpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token/");
    }

    @AfterEach
    void teardownServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @BeforeEach
    void setupTokenFile() throws IOException {
        tokenFile = Files.createTempFile("refresh_token", "");
        Files.writeString(tokenFile, "0123");
    }

    @AfterEach
    void destroyTokenFile() throws IOException {
        if (tokenFile != null) {
            Files.deleteIfExists(tokenFile);
        }
    }

    private LobbyAuthenticator newAuthenticator() throws IOException {
        return new LobbyAuthenticator(tokenFile, tokenEndpoint, CLIENT_ID);
    }

    /** Replace the handler at {@code /token/}. Safe to call multiple times within one test. */
    private void setHandler(final HttpHandler handler) {
        try {
            server.removeContext("/token/");
        } catch (IllegalArgumentException ignored) {
            // No context with that name set yet.
        }
        server.createContext("/token/", new CapturingHandler(handler));
    }

    @Test
    void initialReadWorks() throws IOException {
        newAuthenticator();
    }

    @Test
    void tokenExchangeSucceedsAndRotatesRefreshToken() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();

        setHandler(successResponse("7777", "4567"));
        AccessToken first = authenticator.obtain().get();
        assertEquals("7777", first.token());
        assertEquals("4567", Files.readString(tokenFile));
        assertTrue(requestBody.contains("refresh_token=0123"));

        setHandler(successResponse("8888", "8901"));
        AccessToken second = authenticator.obtain().get();
        assertEquals("8888", second.token());
        assertEquals("8901", Files.readString(tokenFile));
        assertTrue(requestBody.contains("refresh_token=4567"));

        assertEquals("POST", requestMethod);
        assertTrue(requestBody.contains("client_id=" + CLIENT_ID));
    }

    @Test
    void networkFailureSurfacesAsAuthenticationException() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();
        // Stop the server so the connect attempt fails immediately.
        teardownServer();

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> authenticator.obtain().get());
        assertEquals(AuthenticationException.class, e.getCause().getClass());
    }

    @Test
    void missingTokenFileSurfacesAsIOException() throws IOException {
        Files.deleteIfExists(tokenFile);
        assertThrows(IOException.class, this::newAuthenticator);
    }

    @Test
    void badCredentialsSurfaceErrorDescription() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();
        setHandler(errorResponse(400, "invalid_grant", "Invalid grant message"));

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> authenticator.obtain().get());
        assertEquals(AuthenticationException.class, e.getCause().getClass());
        assertEquals("Invalid grant message", e.getCause().getMessage());
    }

    @Test
    void malformedResponseSurfacesAsAuthenticationException() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();
        setHandler(rawResponse(200, "not json at all"));

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> authenticator.obtain().get());
        assertEquals(AuthenticationException.class, e.getCause().getClass());
        // Message must not echo the body verbatim (could carry a token in malformed real cases).
        assertTrue(
                e.getCause().getMessage().contains("non-JSON"),
                "expected 'non-JSON' in message, got: " + e.getCause().getMessage());
    }

    @Test
    void unexpectedJsonShapeSurfacesAsAuthenticationException() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();
        setHandler(rawResponse(200, "{\"something_else\": true}"));

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> authenticator.obtain().get());
        assertEquals(AuthenticationException.class, e.getCause().getClass());
        assertTrue(e.getCause().getMessage().contains("unexpected payload"));
    }

    @Test
    void missingExpiresInDoesNotFail() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();
        // A token response that omits expires_in must not NPE — the access token is still usable;
        // expiry simply defaults to "now" (the lobby enforces its own expiry).
        setHandler(rawResponse(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\"}"));

        AccessToken token = authenticator.obtain().get();
        assertEquals("a", token.token());
    }

    private HttpHandler successResponse(final String accessToken, final String refreshToken) {
        ObjectNode response = mapper.createObjectNode();
        response.put("token_type", "bearer");
        response.put("expires_in", 3600);
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("scope", "openid offline lobby");
        return exchange -> writeJson(exchange, 200, response);
    }

    private HttpHandler errorResponse(
            final int status, final String error, final String description) {
        ObjectNode response = mapper.createObjectNode();
        response.put("error", error);
        response.put("error_description", description);
        return exchange -> writeJson(exchange, status, response);
    }

    private HttpHandler rawResponse(final int status, final String body) {
        return exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    private void writeJson(final HttpExchange exchange, final int status, final ObjectNode payload)
            throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** Wraps the per-test handler so the request method and body are captured for assertions. */
    private final class CapturingHandler implements HttpHandler {
        private final HttpHandler delegate;

        CapturingHandler(final HttpHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            requestMethod = exchange.getRequestMethod();
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = new String(in.readAllBytes());
            }
            delegate.handle(exchange);
            exchange.close();
        }
    }

    @Test
    void exceptionMessageOnBadCredsDoesNotLeakRefreshToken() throws Exception {
        LobbyAuthenticator authenticator = newAuthenticator();
        setHandler(errorResponse(400, "invalid_grant", "token expired"));

        ExecutionException e =
                assertThrows(ExecutionException.class, () -> authenticator.obtain().get());
        String msg = e.getCause().getMessage();
        assertTrue(msg.contains("token expired"), msg);
        assertTrue(!msg.contains("0123"), "exception message must not echo the refresh token");
    }
}
