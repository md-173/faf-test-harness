package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;

/** Factory that picks the right {@link TokenSource} for a given {@link MockClientConfig}. */
public final class TokenSources {

    private TokenSources() {}

    /**
     * Pick a token source for {@code config}. Two credential channels are supported, and {@link
     * MockClientConfig} guarantees exactly one is configured.
     *
     * <ul>
     *   <li>A <b>refresh-token file</b>, exchanged for short-lived access tokens via {@link
     *       LobbyAuthenticator} and rewritten on every rotation. The steady-state headless path.
     *   <li>A <b>pre-signed access-token file</b> (WBS-3.1.6.4), used verbatim by {@link
     *       StaticTokenSource} with no exchange at all. This is what lets a lobby-facing run happen
     *       without a rotating per-developer secret — the difference between a live test one person
     *       can run and one CI can.
     * </ul>
     *
     * <p>A literal-string-only refresh token is not supported: {@link LobbyAuthenticator} persists
     * the rotated refresh token to a file, so a literal-only configuration would silently lose
     * rotation on restart. Callers that only have a literal refresh token should write it to a file
     * before invoking this factory. The access-token channel is a file for a different reason —
     * keeping the token out of the process table — but the shape matches.
     *
     * @param config the validated mock-client configuration
     * @return a {@link TokenSource} ready to use against the handshake
     * @throws AuthenticationException if no usable credential channel is configured, or if the
     *     configured file cannot be read
     */
    public static TokenSource fromConfig(final MockClientConfig config) {
        if (config.oauthAccessTokenFile().isPresent()) {
            return new StaticTokenSource(config.oauthAccessTokenFile().get());
        }
        if (config.oauthRefreshTokenFile() != null) {
            try {
                return new LobbyAuthenticator(
                        config.oauthRefreshTokenFile(),
                        config.oauthTokenUrl(),
                        config.oauthClientId());
            } catch (IOException e) {
                throw new AuthenticationException(
                        "could not read OAuth refresh-token file: "
                                + config.oauthRefreshTokenFile(),
                        e);
            }
        }
        throw new AuthenticationException(
                "no usable OAuth credential channel: supply --oauth-refresh-token-file, or "
                        + "--oauth-access-token-file for a pre-signed token");
    }
}
