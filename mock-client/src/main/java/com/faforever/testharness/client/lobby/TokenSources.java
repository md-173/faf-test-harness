package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;
import java.nio.file.Files;

/** Factory that picks the right {@link TokenSource} for a given {@link MockClientConfig}. */
public final class TokenSources {

    private TokenSources() {}

    /**
     * Pick a token source for {@code config}. Precedence:
     *
     * <ol>
     *   <li>literal {@code oauthAccessToken} — pre-obtained bootstrap stop-gap
     *   <li>{@code oauthTokenFile} contents — same, sourced from a file
     *   <li>{@code oauthRefreshTokenFile} — full {@link LobbyAuthenticator} exchange path
     * </ol>
     *
     * <p>The {@code oauthRefreshToken} literal-string channel is not currently supported by this
     * factory: {@link LobbyAuthenticator} persists the rotated refresh token to a file, so a
     * literal-only configuration would silently lose rotation on restart. Callers that only have a
     * literal refresh token should write it to a file before invoking this factory.
     *
     * @param config the validated mock-client configuration
     * @return a {@link TokenSource} ready to use against the handshake
     * @throws AuthenticationException if no credential channel is supported by this factory
     * @throws IOException if a token file is configured but cannot be read
     */
    public static TokenSource fromConfig(final MockClientConfig config) throws IOException {
        if (config.oauthAccessToken() != null && !config.oauthAccessToken().isBlank()) {
            return new PreObtainedAccessTokenSource(config.oauthAccessToken());
        }
        if (config.oauthTokenFile() != null) {
            String raw = Files.readString(config.oauthTokenFile()).strip();
            return new PreObtainedAccessTokenSource(raw);
        }
        if (config.oauthRefreshTokenFile() != null) {
            return new LobbyAuthenticator(
                    config.oauthRefreshTokenFile(), config.oauthTokenUrl(), config.oauthClientId());
        }
        throw new AuthenticationException(
                "no usable OAuth credential channel: supply --oauth-access-token,"
                        + " --oauth-token-file, or --oauth-refresh-token-file");
    }
}
