package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Class responsible for handing out access tokens and managing control and safe-keeping of refresh
 * tokens.
 */
public class LobbyAuthenticator {

    /** Factor for converting from seconds to milis. */
    private static final long SECONDS_TO_MILLIS = 1000L;

    /** The refresh token. */
    private String refreshToken;

    /** The file where the refresh token is written to, so that it persists across runs. */
    private final Path backupFile;

    /** The URL of the source for obtaining more OAuth2 tokens. */
    private final URI tokenSource;

    /** The UUID of the client. */
    private final String clientID;

    /** Mapper for converting to and from JSON. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Enum for holding status codes. Mainly used to prevent MagicNumber errors, so might be worth
     * reconsidering.
     */
    private enum HttpStatusCodes {
        /** Successful request. */
        OK(200);

        /** Numeric value of status code. */
        private final int value;

        HttpStatusCodes(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    /**
     * Creates a new object for holding refresh tokens and requesting access tokens.
     *
     * @param fromFile file to read (and write) the refresh token.
     * @param tokenSourceUrl when new tokens are needed, they are requested from this url.
     * @param clientID the UUID of the client, passed to {@code tokenSourceUrl} when requesting new
     *     tokens.
     * @throws IOException if {@code fromFile} cannot be read for any reason.
     */
    public LobbyAuthenticator(Path fromFile, URI tokenSourceUrl, String clientID)
            throws IOException {
        this.backupFile = fromFile;
        this.refreshToken = readToken();
        this.tokenSource = tokenSourceUrl;
        this.clientID = clientID;
    }

    private String readToken() throws IOException {
        return Files.readString(backupFile).strip();
    }

    /**
     * Write the new refresh token to the file atomically (i.e. by writing it to a temp file and
     * then moving the file in an atomic operation).
     */
    private void writeToken() throws IOException {
        Path tempFile = backupFile.resolveSibling(backupFile.getFileName() + ".tmp");
        try {
            Files.writeString(tempFile, refreshToken);
            Files.move(
                    tempFile,
                    backupFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // As a fallback when atomic move is not supported.
            Files.move(tempFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Obtain a new access token.
     *
     * @return the new access token.
     */
    public CompletableFuture<AccessToken> getAccessToken() {
        HttpClient client = HttpClient.newHttpClient();
        String body =
                "grant_type=refresh_token"
                        + "&refresh_token="
                        + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                        + "&client_id="
                        + URLEncoder.encode(clientID, StandardCharsets.UTF_8);
        HttpRequest request =
                HttpRequest.newBuilder(tokenSource)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        CompletableFuture<HttpResponse<String>> responseFuture =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        return responseFuture.handle(this::obtainToken);
    }

    private AccessToken obtainToken(HttpResponse<String> response, Throwable thrown) {
        if (thrown != null) {
            throw new AuthenticationException("Did not get response from the server", thrown);
        }

        if (response.statusCode() != HttpStatusCodes.OK.value) {
            // TODO: Better information
            throw new AuthenticationException(response.body());
        }

        try {
            JsonNode parsed = mapper.readTree(response.body());
            if (parsed.has("refresh_token")) {
                // Correct response path.
                refreshToken = parsed.get("refresh_token").asText();
                try {
                    writeToken();
                } catch (IOException e) {
                    throw new AuthenticationException("Could not write token to file", e);
                }
                // Get expiry date in Unix time.
                long expiryDate =
                        (System.currentTimeMillis() / SECONDS_TO_MILLIS)
                                + parsed.get("expires_in").asLong();
                return new AccessToken(parsed.get("access_token").asText(), expiryDate);
            } else if (parsed.has("error")) {
                // Incorrect response path.

                // Default message.
                String message = "Failed to authenticate with no error message from server";
                JsonNode messageNode = parsed.get("error_description");
                if (messageNode != null) {
                    message = messageNode.asText();
                }
                throw new AuthenticationException(message);
            } else {
                // Something else, an unexpected failure.
                throw new AuthenticationException(
                        String.format("Unsupported response: %s", response.body()));
            }
        } catch (JsonProcessingException e) {
            throw new AuthenticationException("Couldn't process the JSON response", e);
        }
    }
}
