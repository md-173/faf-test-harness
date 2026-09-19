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
 *     rotated by Hydra on every use); rewritten atomically on each rotation. A file rather than a
 *     literal token value, because a literal cannot receive the rotated token back and would
 *     silently break on the next run. One of the two credential channels; see {@code
 *     oauthAccessTokenFile} for the other.
 * @param oauthAccessTokenFile path to a file holding a pre-signed access token (sensitive — a live
 *     bearer credential), used verbatim with no exchange and no rotation (WBS-3.1.6.4, #325). The
 *     alternative credential channel to {@code oauthRefreshTokenFile}: exactly one applies, and
 *     where both are configured the higher layer wins (see {@code
 *     MockClientCli#toValidatedConfig}), with a same-layer tie rejected here. A file rather than a
 *     bare flag so the token stays out of the process table and out of CI logs
 * @param uniqueId stable hardware identifier sent in the lobby auth message, sent when {@code
 *     uidBinaryPath} is empty.
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
 * @param queueConfig matchmaking-queue settings (lobby-protocol-spec §4.3 / §10.2); present only
 *     when the operator configured the mock client to queue — empty means this run does not queue
 *     (e.g. it hosts or joins a custom game instead)
 * @param iceRelayDelayMs how long the ICE signal relay holds each relayed candidate before
 *     forwarding it, in milliseconds; {@code 0} (the default) forwards inline. The delayed-ICE half
 *     of WBS-5.1's fault injection. Read through {@link #iceRelayDelay()} rather than directly
 * @param mockGameUdpDropPercent percentage of outbound peer datagrams the launched mock-game
 *     suppresses, passed straight through as its {@code --udp-drop-percent} (WBS-5.1-fix, #322).
 *     {@code 0} (the default) drops nothing. The lossy-link half of WBS-5.1's fault injection, and
 *     the only way to reach it in an orchestrated run — mock-game's own flag is otherwise settable
 *     only by hand
 * @param mockGameCrashAfterSeconds how long after entering a session the launched mock-game halts
 *     itself without an orderly shutdown, standing in for a game crash (WBS-5.2); negative, the
 *     default, never crashes. Passed through to mock-game as {@code --crash-after-seconds}, and
 *     emitted only when set so a run that asks for no fault produces the argv it always did
 */
public record MockClientConfig(
        URI lobbyWebSocketUrl,
        URI oauthTokenUrl,
        URI oauthAuthEndpoint,
        URI oauthRedirectUri,
        String oauthScopes,
        String oauthClientId,
        Path oauthRefreshTokenFile,
        Optional<Path> oauthAccessTokenFile,
        Optional<String> uniqueId,
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
        Optional<GameQueueConfig> queueConfig,
        int iceRelayDelayMs,
        int mockGameUdpDropPercent,
        int mockGameCrashAfterSeconds) {

    /** Upper bound for {@code mockGameUdpDropPercent}; mirrors mock-game's own range check. */
    private static final int MAX_PERCENT = 100;

    /**
     * Validates that exactly one OAuth credential channel is present. There are two: a
     * refresh-token file ({@code oauthRefreshTokenFile}) — the steady-state, headless path,
     * exchanged at {@code oauthTokenUrl} for short-lived JWTs and rewritten on each rotation — and
     * a pre-signed access-token file ({@code oauthAccessTokenFile}), used verbatim with no exchange
     * and no renewal (WBS-3.1.6.4).
     *
     * <p>Only the refresh channel exchanges anything, so only it requires {@code oauthTokenUrl} and
     * {@code oauthClientId}. {@code oauthAuthEndpoint}, {@code oauthRedirectUri} and {@code
     * oauthScopes} are required by neither: they describe the one-time browser bootstrap that mints
     * a refresh token, which is a manual procedure run out of band (lobby-protocol-spec §2) and
     * never by this process. None of the three has a reader anywhere in main source, so demanding
     * them was values invented to pass validation for a flow that does not run here.
     *
     * <p>Both channels configured is rejected rather than resolved here, because by this point the
     * layer each came from is gone. Precedence is applied before construction, in {@code
     * MockClientCli#toValidatedConfig}, so what reaches this constructor with both set is a genuine
     * same-layer tie — and the two renew differently, so picking either would silently choose a
     * failure mode the operator did not.
     *
     * <p>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
     * oauthClientSecret}) are not accepted on this record — the de-risking work in WBS-2.2.10
     * confirmed ROPC and client_credentials are not viable against FAF Hydra. They are rejected
     * earlier by {@link LayeredDefaultProvider} so the user sees a deprecation error pointing at
     * the spec rather than a generic missing-creds error.
     *
     * @throws IllegalArgumentException if any mandatory endpoint/identity field is missing, if
     *     neither credential channel is satisfied, if both are satisfied at the same layer, if
     *     {@code clientVersion} or {@code userAgent} is {@code null} or blank, or if {@code
     *     playerLogin} is {@code null} or blank
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

        // Ensure options are not null.
        uidBinaryPath = uidBinaryPath == null ? Optional.empty() : uidBinaryPath;
        uniqueId = uniqueId == null ? Optional.empty() : uniqueId;

        // Normalised, not merely tolerated. TokenSources.fromConfig dereferences this
        // unguarded, so leaving a null Optional on the record means the two files disagree about
        // whether null is legal — latent today, because every caller passes Optional.ofNullable,
        // and exactly the kind of mismatch that bites after a refactor.
        oauthAccessTokenFile =
                oauthAccessTokenFile == null ? Optional.empty() : oauthAccessTokenFile;
        boolean hasAccessTokenFile = oauthAccessTokenFile.isPresent();
        // Only the refresh-token channel exchanges anything, so only it needs the endpoint and the
        // client id (WBS-3.1.6.4, #325). Demanding them of a pre-signed token would be the same
        // wall the placeholder OAuth flags used to be for the no-lobby diagnostics: values invented
        // to get past validation, never used.
        if (!hasAccessTokenFile) {
            if (oauthTokenUrl == null) {
                missing.add("--oauth-token-url");
            }
            if (oauthClientId == null || oauthClientId.isBlank()) {
                missing.add("--oauth-client-id");
            }
        }
        if (oauthRefreshTokenFile != null && hasAccessTokenFile) {
            // Rejected rather than resolved by precedence: the two channels behave differently —
            // one renews itself and rewrites its file, the other cannot renew at all — so silently
            // picking either would produce a run whose failure mode the operator did not choose.
            throw new IllegalArgumentException(
                    "two OAuth credential channels configured: --oauth-refresh-token-file and "
                            + "--oauth-access-token-file. Supply exactly one. The refresh-token "
                            + "file is the steady-state headless path; the access-token file is a "
                            + "pre-signed token used verbatim, with no exchange and no renewal.");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing required configuration: "
                            + String.join(", ", missing)
                            + ". Supply each via its CLI flag, the matching FAF_MOCK_CLIENT_* "
                            + "environment variable, or a --config file.");
        }
        if (oauthRefreshTokenFile == null && !hasAccessTokenFile) {
            throw new IllegalArgumentException(
                    "no OAuth credentials supplied: set --oauth-refresh-token-file for headless "
                            + "refresh-token rotation, or --oauth-access-token-file to use a "
                            + "pre-signed token as-is. See "
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

        if (uidBinaryPath.isEmpty() && uniqueId.isEmpty()) {
            throw new IllegalArgumentException(
                    "no UID source supplied, set exactly one of "
                            + "--uid-binary-path or --unique-id. "
                            + "Note that --uid-binary-path is "
                            + "required for live lobby communication.");
        }

        if (uidBinaryPath.isPresent() && uniqueId.isPresent()) {
            throw new IllegalArgumentException(
                    "both UID source supplied, set only one of "
                            + "--uid-binary-path or --unique-id. "
                            + "Note that --uid-binary-path is "
                            + "required for live lobby communication.");
        }

        // Hosting, joining and queueing are three ways to spend the same session, and the client
        // sends every one that is configured on the same IDLE entry. Combining them is always a
        // mistake: game_host plus game_matchmaking start puts the client in a custom game while
        // still queued, and being matched from there is exactly what earns a matchmaker violation
        // (#224 operational risk). Checked here rather than with a picocli @ArgGroup so that the
        // JSON config-file path is covered too, not just the CLI flags (#304 review).
        // No null guards: buildHostConfig/buildJoinConfig/buildQueueConfig never return null and
        // nothing else constructs this record with one. Guarding anyway was inconsistent with the
        // rest of this constructor, which does not null-check uidBinaryPath or logFile — and
        // actively harmful, because it classified a null as "role not configured" and let the
        // record construct, turning a constructor-time NPE into a deferred one inside
        // MockClientLifecycle.
        List<String> intents = new ArrayList<>();
        if (hostConfig.isPresent()) {
            intents.add("--host-* (host a custom game)");
        }
        if (joinConfig.isPresent()) {
            intents.add("--target-game-id (join a custom game)");
        }
        if (queueConfig.isPresent()) {
            intents.add("--queue-name (queue for a matchmaker game)");
        }
        if (intents.size() > 1) {
            throw new IllegalArgumentException(
                    "a session can host, join, or queue, but not more than one at once; got "
                            + String.join(" and ", intents)
                            + ". Each is an IDLE entry hook, so configuring several sends"
                            + " conflicting requests on the same entry to IDLE. Keep one and"
                            + " remove the others' flags, environment variables or config-file"
                            + " keys.");
        }

        // Same reasoning, and the same range mock-game enforces on the flag this becomes: a value
        // outside 0-100 is always a typo, and catching it here makes it a usage error rather than
        // a mock-game usage error surfacing as an opaque subprocess exit mid-session.
        if (mockGameUdpDropPercent < 0 || mockGameUdpDropPercent > MAX_PERCENT) {
            throw new IllegalArgumentException(
                    "mockGameUdpDropPercent must be between 0 and 100: " + mockGameUdpDropPercent);
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
