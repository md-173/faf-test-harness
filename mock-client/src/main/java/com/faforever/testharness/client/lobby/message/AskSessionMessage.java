package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outbound {@code ask_session} payload (lobby-protocol-spec.md §3, §10.1) — the first message the
 * client sends after the WebSocket handshake. The server replies with {@link SessionMessage}.
 *
 * <p>Both fields are required by the wire schema. The canonical constructor enforces that {@code
 * version} and {@code userAgent} are non-null and non-blank — a defensive guard against
 * accidentally shipping an empty client identifier, which has historically caused
 * unhelpful-but-not-fatal server-side warnings.
 *
 * @param version client version string
 * @param userAgent client identifier string
 */
@LobbyCommand("ask_session")
public record AskSessionMessage(String version, @JsonProperty("user_agent") String userAgent)
        implements OutboundMessage {

    /** Compact canonical constructor — rejects null or blank strings. */
    public AskSessionMessage {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ask_session.version must be non-blank");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("ask_session.user_agent must be non-blank");
        }
    }
}
