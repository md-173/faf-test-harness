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
 * {@link TokenSource} that exchanges a long-lived refresh token for short-lived JWT access tokens
 * against the configured OAuth2 token endpoint (Hydra in production; see {@code
 * documentation/research/lobby-protocol-spec.md} §2). Hydra rotates the refresh token on every
 * successful exchange, and the rotated value is persisted atomically (temp-file write + rename) to
 * the configured backup file before the new access token is returned.
 *
 * <p>No part of this class logs the refresh token, the access token, or any HTTP response body that
 * could carry them — error messages surface a status code or the OAuth {@code error_description}
 * field only.
 */
public final class LobbyAuthenticator implements TokenSource {

    /** Conversion factor for seconds → milliseconds. */
    private static final long SECONDS_TO_MILLIS = 1000L;

    /** The most recent refresh token. Rotated atomically on every successful exchange. */
    private String refreshToken;

    /** The file the refresh token is persisted to so it survives a restart. */
    private final Path backupFile;

    /** The OAuth2 token endpoint to POST refresh-token exchanges to. */
    private final URI tokenSource;

    /** The OAuth2 public client identifier (UUID) registered on Hydra. */
    private final String clientId;

    /** Jackson mapper for parsing token responses. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** HTTP client reused across every exchange. */
    private final HttpClient http;

    /**
     * Construct an authenticator that reads its initial refresh token from {@code fromFile} and
     * sends future exchanges to {@code tokenSourceUrl}.
     *
     * @param fromFile file holding the refresh token; rewritten atomically on each successful
     *     rotation
     * @param tokenSourceUrl Hydra token endpoint (e.g. {@code
     *     https://hydra.faforever.xyz/oauth2/token})
     * @param clientId the public OAuth2 client UUID
     * @throws IOException if {@code fromFile} cannot be read
     */
    public LobbyAuthenticator(final Path fromFile, final URI tokenSourceUrl, final String clientId)
            throws IOException {
        this.backupFile = fromFile;
        this.refreshToken = readToken();
        this.tokenSource = tokenSourceUrl;
        this.clientId = clientId;
        this.http = HttpClient.newHttpClient();
    }

    private String readToken() throws IOException {
        return Files.readString(backupFile).strip();
    }

    /**
     * Write the new refresh token to the backup file atomically (temp file + rename). Falls back to
     * a non-atomic move on filesystems that don't support atomic moves.
     */
    private void writeToken() throws IOException {
        Path tempFile = backupFile.resolveSibling(backupFile.getFileName() + ".tmp");
        Files.writeString(tempFile, refreshToken);
        try {
            Files.move(
                    tempFile,
                    backupFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Exchange the current refresh token for a fresh access token. On success the rotated refresh
     * token is persisted to the backup file before the future completes — callers can treat the
     * returned {@link AccessToken} as the source of truth without separately committing the
     * rotation.
     *
     * @return future that completes with the new {@link AccessToken}, or completes exceptionally
     *     with {@link AuthenticationException}
     */
    @Override
    public CompletableFuture<AccessToken> obtain() {
        String body =
                "grant_type=refresh_token"
                        + "&refresh_token="
                        + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                        + "&client_id="
                        + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
        HttpRequest request =
                HttpRequest.newBuilder(tokenSource)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle(this::obtainToken);
    }

    private AccessToken obtainToken(final HttpResponse<String> response, final Throwable thrown) {
        if (thrown != null) {
            throw new AuthenticationException(
                    "OAuth token endpoint unreachable: " + thrown.getClass().getSimpleName(),
                    thrown);
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            // Don't echo the body — it might contain tokens if a proxy mangled the response.
            throw new AuthenticationException(
                    "OAuth token endpoint returned non-JSON response (status="
                            + response.statusCode()
                            + ")",
                    e);
        }

        if (parsed.has("access_token") && parsed.has("refresh_token")) {
            refreshToken = parsed.get("refresh_token").asText();
            try {
                writeToken();
            } catch (IOException e) {
                throw new AuthenticationException("could not persist rotated refresh token", e);
            }
            long expiryDate =
                    (System.currentTimeMillis() / SECONDS_TO_MILLIS)
                            + parsed.path("expires_in").asLong(0);
            return new AccessToken(parsed.get("access_token").asText(), expiryDate);
        }

        if (parsed.has("error")) {
            String description =
                    parsed.has("error_description")
                            ? parsed.get("error_description").asText()
                            : parsed.get("error").asText();
            throw new AuthenticationException(description);
        }

        throw new AuthenticationException(
                "OAuth token endpoint returned unexpected payload (status="
                        + response.statusCode()
                        + ")");
    }
}
