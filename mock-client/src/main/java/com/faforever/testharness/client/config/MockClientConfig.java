package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable, validated configuration for the Mock Client. Every other component reads from an
 * instance of this record. Produced exclusively by {@link ConfigLoader}.
 *
 * <p>Authentication uses OAuth2 refresh-token rotation against FAF Hydra (see {@code
 * documentation/research/lobby-protocol-spec.md} §2 and WBS-2.2.10). The mock client targets the
 * seeded {@code FAF Classic Client (Python)} public client; password-grant ("ROPC") and
 * client_credentials are not enabled on any seeded client with {@code lobby} scope and are
 * explicitly rejected by the loader.
 *
 * @param lobbyWebSocketUrl WebSocket endpoint of the FAF lobby server
 * @param oauthTokenUrl OAuth2 token endpoint (Hydra {@code /oauth2/token})
 * @param oauthAuthEndpoint OAuth2 authorization endpoint (Hydra {@code /oauth2/auth}) used by the
 *     one-time bootstrap that mints the refresh token
 * @param oauthRedirectUri Redirect URI registered on the OAuth client
 * @param oauthScopes Space-separated OAuth2 scopes (e.g. {@code openid offline lobby})
 * @param oauthClientId OAuth2 public client identifier
 * @param oauthRefreshToken long-lived refresh token (sensitive — rotated by Hydra on every use)
 * @param oauthRefreshTokenFile path to a file holding the refresh token; rewritten atomically on
 *     each rotation
 * @param oauthAccessToken auxiliary pre-obtained access token (bootstrap output)
 * @param oauthTokenFile auxiliary path to a file containing a pre-obtained access token (bootstrap
 *     output)
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
        URI oauthAuthEndpoint,
        URI oauthRedirectUri,
        String oauthScopes,
        String oauthClientId,
        String oauthRefreshToken,
        Path oauthRefreshTokenFile,
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
     * Validates that an OAuth credential channel is present. The mock client supports two channels:
     *
     * <ul>
     *   <li>Refresh token ({@code oauthRefreshToken} or {@code oauthRefreshTokenFile}) — the
     *       steady-state, headless path. Exchanged at {@code oauthTokenUrl} for short-lived JWTs.
     *   <li>Pre-obtained access token ({@code oauthAccessToken} or {@code oauthTokenFile}) —
     *       auxiliary bootstrap-output path, useful for one-shot smoke tests.
     * </ul>
     *
     * <p>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
     * oauthClientSecret}) are not accepted on this record — the de-risking work in WBS-2.2.10
     * confirmed ROPC and client_credentials are not viable against FAF Hydra. They are rejected
     * earlier by {@link LayeredDefaultProvider} so the user sees a deprecation error pointing at
     * the spec rather than a generic missing-creds error.
     *
     * @throws IllegalArgumentException if neither credential channel is satisfied
     */
    public MockClientConfig {
        boolean hasRefreshToken = oauthRefreshToken != null || oauthRefreshTokenFile != null;
        boolean hasAccessToken = oauthAccessToken != null || oauthTokenFile != null;
        if (!hasRefreshToken && !hasAccessToken) {
            throw new IllegalArgumentException(
                    "no OAuth credentials supplied: set --oauth-refresh-token / "
                            + "--oauth-refresh-token-file for headless refresh-token rotation, "
                            + "or --oauth-access-token / --oauth-token-file to use a "
                            + "pre-obtained bootstrap token. See "
                            + "documentation/research/lobby-protocol-spec.md §2 / WBS-2.2.10 "
                            + "for the one-time bootstrap procedure.");
        }
    }
}
