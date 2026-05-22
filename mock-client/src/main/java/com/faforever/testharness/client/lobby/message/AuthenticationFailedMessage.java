package com.faforever.testharness.client.lobby.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound {@code authentication_failed} payload (lobby-protocol-spec.md §3, §10.1) — the terminal
 * negative response to {@link AuthMessage}. The server closes the connection shortly after sending
 * this; the FSM should treat receipt as a stop signal for the auth handshake.
 *
 * @param text human-readable failure reason supplied by the server
 */
@LobbyCommand("authentication_failed")
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationFailedMessage(String text) implements InboundMessage {}
