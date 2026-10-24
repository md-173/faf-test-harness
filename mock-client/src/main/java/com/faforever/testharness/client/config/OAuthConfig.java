package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The OAuth part of {@link MockClientConfig}: the endpoints and public client the refresh-token
 * exchange uses, and the two credential channels.
 *
 * <p>A plain carrier. Which of these are required, and whether both channels may be set, is checked
 * by {@link MockClientConfig}'s compact constructor together with the rest of the configuration, so
 * building this record never changes which error a bad configuration reports.
 *
 * @param tokenUrl OAuth2 token endpoint (Hydra {@code /oauth2/token})
 * @param authEndpoint OAuth2 authorization endpoint (Hydra {@code /oauth2/auth}) used by the
 *     one-time bootstrap that mints the refresh token
 * @param redirectUri Redirect URI registered on the OAuth client
 * @param scopes Space-separated OAuth2 scopes (e.g. {@code openid offline lobby})
 * @param clientId OAuth2 public client identifier
 * @param refreshTokenFile path to a file holding the long-lived refresh token (sensitive, rotated
 *     by Hydra on every use); rewritten atomically on each rotation. A file rather than a literal
 *     token value, because a literal cannot receive the rotated token back and would silently break
 *     on the next run. One of the two credential channels; see {@code accessTokenFile} for the
 *     other.
 * @param accessTokenFile path to a file holding a pre-signed access token (sensitive: a live bearer
 *     credential), used verbatim with no exchange and no rotation (WBS-3.1.6.4, #325). The
 *     alternative credential channel to {@code refreshTokenFile}: exactly one applies, and where
 *     both are configured the higher layer wins (see {@code MockClientCli#toValidatedConfig}), with
 *     a same-layer tie rejected by {@link MockClientConfig}. A file rather than a bare flag so the
 *     token stays out of the process table and out of CI logs
 */
public record OAuthConfig(
        URI tokenUrl,
        URI authEndpoint,
        URI redirectUri,
        String scopes,
        String clientId,
        Path refreshTokenFile,
        Optional<Path> accessTokenFile) {

    /** Normalises a {@code null} {@code accessTokenFile} to empty, and checks nothing else. */
    public OAuthConfig {
        // Normalised, not merely tolerated. TokenSources.fromConfig dereferences this
        // unguarded, so leaving a null Optional on the record means the two files disagree about
        // whether null is legal: latent today, because every caller passes Optional.ofNullable,
        // and exactly the kind of mismatch that bites after a refactor.
        accessTokenFile = accessTokenFile == null ? Optional.empty() : accessTokenFile;
    }
}
