package com.faforever.testharness.client.lobby;

import java.util.concurrent.CompletableFuture;

/**
 * {@link TokenSource} that returns a token supplied out-of-band — typically the {@code
 * access_token} captured during the one-time {@code authorization_code} bootstrap and persisted to
 * config (see {@code documentation/research/lobby-protocol-spec.md} §2, {@code
 * MockClientConfig.oauthAccessToken}).
 *
 * <p>Stop-gap: the token is returned as-is on every call with {@code expiryDate = Long.MAX_VALUE},
 * because pre-obtained tokens have no exchange step where Hydra would communicate {@code
 * expires_in}. The lobby server enforces its own expiry; an expired token surfaces as {@code
 * authentication_failed} during the handshake, not here.
 */
public final class PreObtainedAccessTokenSource implements TokenSource {

    /** The pre-obtained JWT bearer token. Never logged. */
    private final AccessToken token;

    /**
     * Construct a source that always returns {@code rawToken}.
     *
     * @param rawToken the JWT bearer token; must not be {@code null} or blank
     * @throws IllegalArgumentException if {@code rawToken} is {@code null} or blank
     */
    public PreObtainedAccessTokenSource(final String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("pre-obtained access token must not be blank");
        }
        this.token = new AccessToken(rawToken, Long.MAX_VALUE);
    }

    @Override
    public CompletableFuture<AccessToken> obtain() {
        return CompletableFuture.completedFuture(token);
    }
}
