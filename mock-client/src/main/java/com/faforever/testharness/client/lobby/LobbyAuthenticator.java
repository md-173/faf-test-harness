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
    private void writeToken() {
        Path tempFile = backupFile.resolveSibling(backupFile.getFileName() + ".tmp");
        try {
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
        } catch (IOException e) {
            // TODO
            System.out.println("ERROR");
        }
    }

    /**
     * Obtain a new access token.
     *
     * @return the new access token.
     */
    public AccessToken getAccessToken() {
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
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HttpStatusCodes.OK.value) {
                // TODO
                throw new RuntimeException("Blah");
            }

            JsonNode parsed = mapper.readTree(response.body());
            refreshToken = parsed.get("refresh_token").asText();
            writeToken();
            // Get expiry date in Unix time.
            long expiryDate =
                    (System.currentTimeMillis() / SECONDS_TO_MILLIS)
                            + parsed.get("expires_in").asLong();
            return new AccessToken(parsed.get("access_token").asText(), expiryDate);
        } catch (JsonProcessingException e) {
            // TODO
            return null;
        } catch (IOException e) {
            // TODO
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
