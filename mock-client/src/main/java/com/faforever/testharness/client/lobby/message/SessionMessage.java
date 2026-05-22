package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound {@code session} payload (lobby-protocol-spec.md §3, §10.1) — the server's response to
 * {@link AskSessionMessage}. Carries the session ID the client must echo back in {@link
 * AuthMessage}.
 *
 * <p>{@link JsonIgnoreProperties} drops the {@code command} field (present in every inbound frame
 * but not modelled on the record) and any forward-compatible fields the server may add.
 *
 * <p>{@code session} is a boxed {@link Long}, not a primitive, so a frame that omits it decodes to
 * {@code null} rather than silently to {@code 0}; the canonical constructor then rejects it and the
 * dispatcher drops the frame. A primitive would mask a missing field as session id 0, which the
 * auth handshake would echo straight back to the server.
 *
 * @param session session ID assigned by the server; required
 */
@LobbyCommand("session")
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionMessage(Long session) implements InboundMessage {

    /** Compact canonical constructor — rejects a missing {@code session}. */
    public SessionMessage {
        if (session == null) {
            throw new IllegalArgumentException("session.session is required");
        }
    }
}
