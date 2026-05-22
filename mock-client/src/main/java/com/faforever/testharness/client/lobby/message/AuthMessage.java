package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outbound {@code auth} payload (lobby-protocol-spec.md §3, §10.1) — authenticates the session
 * established by {@link AskSessionMessage} / {@link SessionMessage}. The server replies with either
 * {@link WelcomeMessage} (success) or {@link AuthenticationFailedMessage} (failure).
 *
 * <p>All three fields are required. The canonical constructor enforces non-blank {@code token} and
 * {@code uniqueId}, and rejects a missing {@code session}. {@code token} is a JWT and is not
 * format-checked here — Hydra's signing is verified server-side; an invalid token will simply
 * produce {@link AuthenticationFailedMessage}.
 *
 * @param token OAuth JWT bearer token from Hydra (§2)
 * @param uniqueId hardware-identifier hash; the Mock Client uses a stable synthetic value
 * @param session session ID echoed from the prior {@link SessionMessage}
 */
@LobbyCommand("auth")
public record AuthMessage(String token, @JsonProperty("unique_id") String uniqueId, long session)
        implements OutboundMessage {

    /** Compact canonical constructor — validates required fields. */
    public AuthMessage {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("auth.token must be non-blank");
        }
        if (uniqueId == null || uniqueId.isBlank()) {
            throw new IllegalArgumentException("auth.unique_id must be non-blank");
        }
    }
}
