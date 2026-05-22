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
 * @param session session ID assigned by the server
 */
@LobbyCommand("session")
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionMessage(long session) implements InboundMessage {}
