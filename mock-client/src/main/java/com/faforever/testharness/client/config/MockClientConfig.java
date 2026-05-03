package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable, validated configuration for the Mock Client. Every other component reads from an
 * instance of this record. Produced exclusively by {@link ConfigLoader}.
 *
 * @param lobbyWebSocketUrl WebSocket endpoint of the FAF lobby server
 * @param oauthTokenUrl OAuth2 token endpoint
 * @param oauthClientId OAuth2 client identifier
 * @param oauthClientSecret OAuth2 client secret (password-grant)
 * @param oauthUsername OAuth username (password-grant)
 * @param oauthPassword OAuth password (password-grant)
 * @param oauthAccessToken pre-obtained access token
 * @param oauthTokenFile path to a file containing a pre-obtained access token
 * @param uniqueId stable hardware identifier sent in the lobby auth message
 * @param iceAdapterBinaryPath path to the faf-ice-adapter executable
 * @param mockGameBinaryPath path to the mock-game executable
 * @param iceAdapterRpcPort local JSON-RPC port exposed by faf-ice-adapter
 * @param iceAdapterGpgNetPort local GPGNet port exposed by faf-ice-adapter
 * @param logLevel minimum log level
 * @param logFile optional JSONL log file path
 * @param playerIdOverride optional player ID override for deterministic local testing
 */
public record MockClientConfig(
        URI lobbyWebSocketUrl,
        URI oauthTokenUrl,
        String oauthClientId,
        String oauthClientSecret,
        String oauthUsername,
        String oauthPassword,
        String oauthAccessToken,
        Path oauthTokenFile,
        String uniqueId,
        Path iceAdapterBinaryPath,
        Path mockGameBinaryPath,
        int iceAdapterRpcPort,
        int iceAdapterGpgNetPort,
        String logLevel,
        Optional<Path> logFile,
        OptionalInt playerIdOverride) {

    /**
     * Validates that OAuth credentials are present in at least one usable form: either a
     * pre-obtained access token ({@code oauthAccessToken} or {@code oauthTokenFile}), or a full
     * password-grant set ({@code oauthUsername}, {@code oauthPassword}, and {@code
     * oauthClientSecret}).
     *
     * @throws IllegalArgumentException if neither form is satisfied
     */
    public MockClientConfig {
        boolean hasToken = oauthAccessToken != null || oauthTokenFile != null;
        boolean hasPasswordGrant =
                oauthUsername != null && oauthPassword != null && oauthClientSecret != null;
        if (!hasToken && !hasPasswordGrant) {
            throw new IllegalArgumentException(
                    "no OAuth credentials supplied: set --oauth-access-token / "
                            + "--oauth-token-file, or --oauth-username + --oauth-password "
                            + "+ --oauth-client-secret");
        }
    }
}
