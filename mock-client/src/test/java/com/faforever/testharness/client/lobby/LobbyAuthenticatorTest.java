package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

public final class LobbyAuthenticatorTest {

    private final URI serverAddress = URI.create("http://127.0.0.1");
    private HttpServer server;
    private Path tokenFile;

    // Variables used to store server request info for later assertions.
    private String requestMethod;
    private String requestBody;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    private void setupServer() throws IOException {
        try {
            // Backlog set to 1. Only one connection at a time.
            server = HttpServer.create(new InetSocketAddress(serverAddress.getPath(), 8080), 1);
        } catch (IOException e) {
            System.out.println(
                    "Error with creating the necessary http server, cannot proceed with test.");
            throw e;
        }
        server.start();
    }

    private class CustomHandler implements HttpHandler {
        // Variable used for tests to dictate what server should return next.
        private ObjectNode response;

        CustomHandler(ObjectNode response) {
            this.response = response;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Write request method for testing
            requestMethod = exchange.getRequestMethod();
            InputStream input = exchange.getRequestBody();
            byte[] buf = new byte[256];
            int read = input.read(buf);
            if (input.available() > 0) {
                // TODO: Likely freezes the test
                fail("Input too large");
            }
            requestBody = new String(buf, 0, read);
            input.close();
            String responseBody = mapper.writeValueAsString(response);
            exchange.sendResponseHeaders(200, responseBody.length());
            OutputStream os = exchange.getResponseBody();
            os.write(responseBody.getBytes());
            exchange.close();
        }
    }

    @AfterEach
    private void teardownServer() {
        if (server != null) {
            server.stop(1000);
            server = null;
        }
    }

    @BeforeEach
    private void setupTokenFile() throws IOException {
        try {
            tokenFile = Files.createTempFile("refresh_token", "");
            Files.writeString(tokenFile, "0123");
        } catch (IOException e) {
            System.out.println(
                    "Error with writing the necessary token file, cannot proceed with test.");
            throw e;
        }
    }

    @AfterEach
    private void destroyTokenFile() {
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            System.out.println("Error erasing dummy token file. Noncritical");
        }
    }

    @Test
    void initialReadWorks() throws IOException {
        try {
            LobbyAuthenticator authenticator =
                    new LobbyAuthenticator(
                            tokenFile, serverAddress.resolve("/token/"), "0001-0002-0003-0004");
        } catch (IOException e) {
            fail(String.format("Initial read did not work due to %s", e.getMessage()));
        }
    }

    @Test
    void tokenExchange() throws IOException {
        try {
            LobbyAuthenticator authenticator =
                    new LobbyAuthenticator(
                            tokenFile,
                            URI.create("http://127.0.0.1:8080/token/"),
                            "0001-0002-0003-0004");

            try {
                generateSuccessfulResponse("7777", "4567");
                AccessToken access = authenticator.getAccessToken().get();
                // Should have received this value for access token.
                assertEquals("7777", access.token());
                String refresh = Files.readString(tokenFile);
                assertEquals("4567", refresh);

                // Old refresh token given in request
                assertTrue(requestBody.contains("refresh_token=0123"));

                generateSuccessfulResponse("8888", "8901");
                access = authenticator.getAccessToken().get();
                // Should have received new values for tokens.
                assertEquals("8888", access.token());
                refresh = Files.readString(tokenFile);
                assertEquals("8901", refresh);

                // Old refresh token given in request
                assertTrue(requestBody.contains("refresh_token=4567"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Got interrupted");
            } catch (ExecutionException e) {
                fail(String.format("Could not get access token due to %s", e.getMessage()));
            } catch (IOException e) {
                fail("Could not read refresh file again. Likely broken by LobbyAuthenticator.");
            }

            assertEquals("POST", requestMethod);
            // Correct client id
            assertTrue(requestBody.contains("client_id=0001-0002-0003-0004"));

        } catch (IOException e) {
            fail(String.format("Initial read did not work due to %s", e.getMessage()));
        }
    }

    @Test
    void serverDisconnectFails() throws IOException {
        // Stop the server now.
        teardownServer();

        try {
            LobbyAuthenticator authenticator =
                    new LobbyAuthenticator(
                            tokenFile,
                            URI.create("http://127.0.0.1:8080/token/"),
                            "0001-0002-0003-0004");

            ExecutionException e =
                    assertThrows(
                            ExecutionException.class, () -> authenticator.getAccessToken().get());
            assertEquals(AuthenticationException.class, e.getCause().getClass());
        } catch (IOException e) {
            fail(String.format("Initial read did not work due to %s", e.getMessage()));
        }
    }

    @Test
    void fileReadFails() {
        // Delete file now.
        destroyTokenFile();

        IOException e =
                assertThrows(
                        IOException.class,
                        () ->
                                new LobbyAuthenticator(
                                        tokenFile,
                                        URI.create("http://127.0.0.1:8080/token/"),
                                        "0001-0002-0003-0004"));
    }

    @Test
    void unsuccessfulResponse() {
        try {
            LobbyAuthenticator authenticator =
                    new LobbyAuthenticator(
                            tokenFile,
                            URI.create("http://127.0.0.1:8080/token/"),
                            "0001-0002-0003-0004");

            generateUnsuccessfulResponse();
            ExecutionException e =
                    assertThrows(
                            ExecutionException.class, () -> authenticator.getAccessToken().get());
            assertEquals(AuthenticationException.class, e.getCause().getClass());
            assertEquals("Invalid grant message", e.getCause().getMessage());

            assertEquals("POST", requestMethod);
            // Correct client id
            assertTrue(requestBody.contains("client_id=0001-0002-0003-0004"));

        } catch (IOException e) {
            fail(String.format("Initial read did not work due to %s", e.getMessage()));
        }
    }

    // Tell the server to respond with a successful response with the given tokens.
    private void generateSuccessfulResponse(String accessToken, String refreshToken) {
        ObjectNode response = mapper.createObjectNode();
        response.put("token_type", "bearer");
        // An hour
        response.put("expires_in", 3600);
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("scope", "openid offline lobby");
        try {
            server.removeContext("/token/");
        } catch (IllegalArgumentException e) {
            // No context with that name as been set, continue as normal
        }
        server.createContext("/token/", new CustomHandler(response));
    }

    // Tell the server to respond with an error.
    private void generateUnsuccessfulResponse() {
        ObjectNode response = mapper.createObjectNode();
        response.put("error", "invalid_grant");
        response.put("error_description", "Invalid grant message");
        try {
            server.removeContext("/token/");
        } catch (IllegalArgumentException e) {
            // No context with that name as been set, continue as normal
        }
        server.createContext("/token/", new CustomHandler(response));
    }
}
