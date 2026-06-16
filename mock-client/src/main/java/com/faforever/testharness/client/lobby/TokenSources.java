package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;

/** Factory that picks the right {@link TokenSource} for a given {@link MockClientConfig}. */
public final class TokenSources {

    private TokenSources() {}

    /**
     * Pick a token source for {@code config}. The only supported credential channel is a
     * refresh-token file, exchanged for short-lived access tokens via {@link LobbyAuthenticator}.
     *
     * <p>A literal-string-only refresh token is not supported: {@link LobbyAuthenticator} persists
     * the rotated refresh token to a file, so a literal-only configuration would silently lose
     * rotation on restart. Callers that only have a literal refresh token should write it to a file
     * before invoking this factory.
     *
     * @param config the validated mock-client configuration
     * @return a {@link TokenSource} ready to use against the handshake
     * @throws AuthenticationException if no usable credential channel is configured, or if the
     *     refresh-token file cannot be read
     */
    public static TokenSource fromConfig(final MockClientConfig config) {
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
                "no usable OAuth credential channel: supply --oauth-refresh-token-file");
    }
}
