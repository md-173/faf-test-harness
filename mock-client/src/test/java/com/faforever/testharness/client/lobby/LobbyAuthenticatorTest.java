package com.faforever.testharness.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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

        server.createContext(
                "/token/",
                exchange -> {
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
                    ObjectMapper mapper = new ObjectMapper();
                    ObjectNode response = mapper.createObjectNode();
                    response.put("access_token", "7777");
                    response.put("token_type", "bearer");
                    // An hour
                    response.put("expires_in", 3600);
                    // New refresh token.
                    response.put("refresh_token", "4567");
                    response.put("scope", "openid offline lobby");
                    String responseBody = mapper.writeValueAsString(response);
                    exchange.sendResponseHeaders(200, responseBody.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(responseBody.getBytes());
                    exchange.close();
                });
        server.start();
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
            String access = authenticator.getAccessToken();
            assertEquals("POST", requestMethod);

            // Old refresh token given in request
            assertTrue(requestBody.contains("refresh_token=0123"));
            // Correct client id
            assertTrue(requestBody.contains("client_id=0001-0002-0003-0004"));

            // Should have received this value for access token.
            assertEquals("7777", access);

            try {
                String refresh = Files.readString(tokenFile);
                assertEquals("4567", refresh);
            } catch (IOException e) {
                fail("Could not read refresh file again. Likely broken by LobbyAuthenticator.");
            }

        } catch (IOException e) {
            fail(String.format("Initial read did not work due to %s", e.getMessage()));
        }
    }
}
