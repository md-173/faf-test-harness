package com.faforever.testharness.client.lobby;

import java.util.concurrent.CompletableFuture;

/**
 * Supplier of OAuth access tokens for the lobby auth handshake. Implementations either return a
 * pre-obtained token verbatim (see {@link PreObtainedAccessTokenSource}) or perform a token
 * exchange against the configured Hydra endpoint (see {@link LobbyAuthenticator}).
 *
 * <p>The result is always a {@link CompletableFuture} so that callers can chain network I/O without
 * blocking the WebSocket listener thread. Failures are surfaced as {@link AuthenticationException}
 * completing the future exceptionally.
 */
@FunctionalInterface
public interface TokenSource {

    /**
     * Obtain an access token. May reuse a cached token or perform a network exchange — callers must
     * not assume either.
     *
     * @return future that completes with the token, or completes exceptionally with {@link
     *     AuthenticationException}
     */
    CompletableFuture<AccessToken> obtain();
}
