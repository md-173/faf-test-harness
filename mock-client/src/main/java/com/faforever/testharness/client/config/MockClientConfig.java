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
 * @param uniqueId stable hardware identifier sent in the lobby auth message
 * @param clientVersion client version string sent in the {@code ask_session} message (a required
 *     field of that command; lobby-protocol-spec.md §3)
 * @param userAgent client identifier string sent in the {@code ask_session} message (a required
 *     field of that command; lobby-protocol-spec.md §3)
 * @param iceAdapterBinaryPath path to the faf-ice-adapter executable
 * @param mockGameBinaryPath path to the mock-game executable
 * @param iceAdapterRpcPort local JSON-RPC port exposed by faf-ice-adapter
 * @param iceAdapterGpgNetPort local GPGNet port exposed by faf-ice-adapter
 * @param iceAdapterLobbyPort local UDP port the game lobby uses for game traffic; passed to
 *     faf-ice-adapter as {@code --lobby-port}
 * @param iceAdapterGameId game ID passed to faf-ice-adapter as {@code --game-id} (required by the
 *     adapter); a default for the {@code launch-ice}/{@code ice-smoke} diagnostics, overridden by
 *     the lobby {@code game_launch.uid} during a full {@code run} session
 * @param logLevel minimum log level
 * @param logFile optional JSONL log file path
 * @param playerIdOverride optional player ID override for deterministic local testing
 * @param playerLogin local player login passed to faf-ice-adapter as {@code --login}. Used directly
 *     by the standalone {@code launch-ice} / {@code ice-smoke} diagnostics; during a full {@code
 *     run} session the lobby {@code welcome.me.login} is the authoritative identity (json-rpc-spec
 *     §8.1), so this value is a default that orchestration may override.
 * @param hostConfig host-a-custom-game settings (lobby-protocol-spec §4.1 / §10.2); present only
 *     when the operator configured the mock client to host — empty means this run does not host
 *     (e.g. it joins an existing game instead)
 * @param joinConfig join-an-existing-game settings (lobby-protocol-spec §4.2 / §10.2); present only
 *     when the operator configured the mock client to join — empty means this run does not join
 *     (e.g. it hosts a game instead)
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
        String uniqueId,
        String clientVersion,
        String userAgent,
        Path iceAdapterBinaryPath,
        Path mockGameBinaryPath,
        int iceAdapterRpcPort,
        int iceAdapterGpgNetPort,
        int iceAdapterLobbyPort,
        int iceAdapterGameId,
        String logLevel,
        Optional<Path> logFile,
        OptionalInt playerIdOverride,
        String playerLogin,
        Optional<GameHostConfig> hostConfig,
        Optional<GameJoinConfig> joinConfig) {

    /**
     * Validates that an OAuth credential channel is present. The mock client supports one channel:
     * a refresh token ({@code oauthRefreshToken} or {@code oauthRefreshTokenFile}) — the
     * steady-state, headless path, exchanged at {@code oauthTokenUrl} for short-lived JWTs.
     *
     * <p>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
     * oauthClientSecret}) are not accepted on this record — the de-risking work in WBS-2.2.10
     * confirmed ROPC and client_credentials are not viable against FAF Hydra. They are rejected
     * earlier by {@link LayeredDefaultProvider} so the user sees a deprecation error pointing at
     * the spec rather than a generic missing-creds error.
     *
     * @throws IllegalArgumentException if neither credential channel is satisfied, if {@code
     *     clientVersion} or {@code userAgent} is {@code null} or blank, or if {@code playerLogin}
     *     is {@code null} or blank
     */
    public MockClientConfig {
        boolean hasRefreshToken = oauthRefreshToken != null || oauthRefreshTokenFile != null;
        if (!hasRefreshToken) {
            throw new IllegalArgumentException(
                    "no OAuth credentials supplied: set --oauth-refresh-token or "
                            + "--oauth-refresh-token-file for headless refresh-token rotation. See "
                            + "documentation/research/lobby-protocol-spec.md §2 / WBS-2.2.10 "
                            + "for the one-time bootstrap procedure.");
        }
        // clientVersion and userAgent are required arguments of the lobby ask_session command; a
        // blank value (reachable via a JSON config file even though the CLI flags have defaults)
        // would otherwise be sent verbatim and rejected by the lobby mid-handshake.
        if (clientVersion == null || clientVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "clientVersion must not be blank: it is sent as the ask_session 'version' "
                            + "field. Set --client-version or remove the empty value from the "
                            + "config file.");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException(
                    "userAgent must not be blank: it is sent as the ask_session 'user_agent' "
                            + "field. Set --user-agent or remove the empty value from the config "
                            + "file.");
        }
        // playerLogin is passed verbatim to faf-ice-adapter as --login; a blank value (reachable
        // via a JSON config file even though the CLI flag has a default) would otherwise surface
        // as an opaque ProcessBuilder failure once the launcher builds the argument list.
        if (playerLogin == null || playerLogin.isBlank()) {
            throw new IllegalArgumentException(
                    "playerLogin must not be blank: it is passed to faf-ice-adapter as --login. "
                            + "Set --player-login or remove the empty value from the config file.");
        }
    }
}
