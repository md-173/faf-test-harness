package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * @param oauthRefreshTokenFile path to a file holding the long-lived refresh token (sensitive —
 *     rotated by Hydra on every use); rewritten atomically on each rotation. The file is the only
 *     credential channel: a literal token value cannot receive the rotated token back, so it would
 *     silently break on the next run.
 * @param uniqueId stable hardware identifier sent in the lobby auth message
 * @param clientVersion client version string sent in the {@code ask_session} message (a required
 *     field of that command; lobby-protocol-spec.md §3)
 * @param userAgent client identifier string sent in the {@code ask_session} message (a required
 *     field of that command; lobby-protocol-spec.md §3)
 * @param uidBinaryPath optional path to the FAF {@code faf-uid} binary. When present, the auth
 *     handshake runs {@code <uidBinaryPath> <session>} and uses its output as the {@code unique_id}
 *     (the lobby's policy/anti-cheat server requires a real RSA-encrypted UID, not a plain
 *     placeholder; lobby-protocol-spec.md §3). When empty, the static {@code uniqueId} is sent.
 * @param iceAdapterBinaryPath path to the faf-ice-adapter executable
 * @param mockGameBinaryPath path to the mock-game executable
 * @param iceAdapterRpcPort local JSON-RPC port exposed by faf-ice-adapter
 * @param iceAdapterGpgNetPort local GPGNet port exposed by faf-ice-adapter
 * @param iceAdapterLobbyPort local UDP port the game lobby uses for game traffic; passed to
 *     faf-ice-adapter as {@code --lobby-port}
 * @param iceAdapterGameId game ID passed to faf-ice-adapter as {@code --game-id} (required by the
 *     adapter); a default for the {@code launch-ice} diagnostic, overridden by the lobby {@code
 *     game_launch.uid} during a full {@code run} session
 * @param mockGameLaunchDelaySeconds how long mock-game sits in the lobby before starting the match
 *     on its own, passed straight through as its {@code --launch-delay-seconds} (WBS-4.3.1).
 *     Negative disables auto-launch, which is what a multi-peer session needs: the FAF server
 *     refuses a {@code game_join} once the host has reported {@code GameState Launching}, so a host
 *     on a timer makes itself unjoinable while the joiner is still booting
 * @param logLevel minimum log level
 * @param logFile optional JSONL log file path
 * @param playerIdOverride optional player ID override for deterministic local testing. Applies to
 *     the standalone {@code launch-ice} / {@code launch-game} diagnostics only. A full {@code run}
 *     session launches under the lobby {@code welcome.me.id} instead, since the adapter's {@code
 *     --id} is what tells the game its own identity (WBS-3.1.2.9)
 * @param playerLogin local player login passed to faf-ice-adapter as {@code --login} and to
 *     mock-game as {@code --player-login}. Used directly by the standalone {@code launch-ice} /
 *     {@code launch-game} diagnostics; during a full {@code run} session the lobby {@code
 *     welcome.me.login} is the authoritative identity (json-rpc-spec §8.1), so this value is a
 *     default that orchestration may override.
 * @param hostConfig host-a-custom-game settings (lobby-protocol-spec §4.1 / §10.2); present only
 *     when the operator configured the mock client to host — empty means this run does not host
 *     (e.g. it joins an existing game instead)
 * @param joinConfig join-an-existing-game settings (lobby-protocol-spec §4.2 / §10.2); present only
 *     when the operator configured the mock client to join — empty means this run does not join
 *     (e.g. it hosts a game instead)
 * @param iceRelayDelayMs how long the ICE signal relay holds each relayed candidate before
 *     forwarding it, in milliseconds; {@code 0} (the default) forwards inline. The delayed-ICE half
 *     of WBS-5.1's fault injection. Read through {@link #iceRelayDelay()} rather than directly
 */
public record MockClientConfig(
        URI lobbyWebSocketUrl,
        URI oauthTokenUrl,
        URI oauthAuthEndpoint,
        URI oauthRedirectUri,
        String oauthScopes,
        String oauthClientId,
        Path oauthRefreshTokenFile,
        String uniqueId,
        String clientVersion,
        String userAgent,
        Optional<Path> uidBinaryPath,
        Path iceAdapterBinaryPath,
        Path mockGameBinaryPath,
        int iceAdapterRpcPort,
        int iceAdapterGpgNetPort,
        int iceAdapterLobbyPort,
        int iceAdapterGameId,
        int mockGameLaunchDelaySeconds,
        String logLevel,
        Optional<Path> logFile,
        OptionalInt playerIdOverride,
        String playerLogin,
        Optional<GameHostConfig> hostConfig,
        Optional<GameJoinConfig> joinConfig,
        int iceRelayDelayMs) {

    /**
     * Validates that an OAuth credential channel is present. The mock client supports one channel:
     * a refresh-token file ({@code oauthRefreshTokenFile}) — the steady-state, headless path,
     * exchanged at {@code oauthTokenUrl} for short-lived JWTs and rewritten on each rotation.
     *
     * <p>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
     * oauthClientSecret}) are not accepted on this record — the de-risking work in WBS-2.2.10
     * confirmed ROPC and client_credentials are not viable against FAF Hydra. They are rejected
     * earlier by {@link LayeredDefaultProvider} so the user sees a deprecation error pointing at
     * the spec rather than a generic missing-creds error.
     *
     * @throws IllegalArgumentException if any mandatory endpoint/identity field is missing, if
     *     neither credential channel is satisfied, if {@code clientVersion} or {@code userAgent} is
     *     {@code null} or blank, or if {@code playerLogin} is {@code null} or blank
     */
    public MockClientConfig {
        // Mandatory endpoint/identity fields. These are intentionally NOT marked required = true on
        // the picocli options: picocli enforces required on INHERIT-scoped options at the
        // subcommand
        // level before consulting the default-value provider, which would make env-var and
        // config-file values unreachable for every subcommand. Validating here lets those layers
        // populate the fields first, while a genuinely missing value still surfaces as a clean
        // usage error (toValidatedConfig wraps this as a picocli ParameterException).
        List<String> missing = new ArrayList<>();
        if (lobbyWebSocketUrl == null) {
            missing.add("--lobby-websocket-url");
        }
        if (oauthTokenUrl == null) {
            missing.add("--oauth-token-url");
        }
        if (oauthAuthEndpoint == null) {
            missing.add("--oauth-auth-endpoint");
        }
        if (oauthRedirectUri == null) {
            missing.add("--oauth-redirect-uri");
        }
        if (oauthScopes == null || oauthScopes.isBlank()) {
            missing.add("--oauth-scopes");
        }
        if (oauthClientId == null || oauthClientId.isBlank()) {
            missing.add("--oauth-client-id");
        }
        if (uniqueId == null || uniqueId.isBlank()) {
            missing.add("--unique-id");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing required configuration: "
                            + String.join(", ", missing)
                            + ". Supply each via its CLI flag, the matching FAF_MOCK_CLIENT_* "
                            + "environment variable, or a --config file.");
        }

        if (oauthRefreshTokenFile == null) {
            throw new IllegalArgumentException(
                    "no OAuth credentials supplied: set --oauth-refresh-token-file for headless "
                            + "refresh-token rotation. See "
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
        // A negative delay is always a typo. Rejecting it here rather than in IceSignalRelay turns
        // it into a usage error at parse time instead of an exception from inside a live session.
        if (iceRelayDelayMs < 0) {
            throw new IllegalArgumentException(
                    "iceRelayDelayMs must not be negative: " + iceRelayDelayMs);
        }
    }

    /**
     * The ICE relay's forward delay as {@link com.faforever.testharness.client.ice.IceSignalRelay}
     * wants it (WBS-5.1).
     *
     * @return the delay to hold each relayed candidate for; {@link Duration#ZERO} forwards inline
     */
    public Duration iceRelayDelay() {
        return Duration.ofMillis(iceRelayDelayMs);
    }
}
