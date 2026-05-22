/**
 * WebSocket transport and typed-message layer for the FAF lobby protocol.
 *
 * <ul>
 *   <li>{@link com.faforever.testharness.client.lobby.LobbyConnection} — raw-frame WebSocket
 *       transport (3.1.1.1): JSON encode/decode, auto pong, single handler per command.
 *   <li>{@link com.faforever.testharness.client.lobby.LobbyMessageHandler} — JsonNode-level handler
 *       interface registered with the connection.
 *   <li>{@link com.faforever.testharness.client.lobby.LobbyMessageDispatcher} — typed inbound
 *       router on top of the connection: decodes JSON into the appropriate {@link
 *       com.faforever.testharness.client.lobby.message.InboundMessage} record and fans out to all
 *       registered consumers.
 *   <li>{@link com.faforever.testharness.client.lobby.LobbyMessageSender} — typed outbound encoder:
 *       takes an {@link com.faforever.testharness.client.lobby.message.OutboundMessage}, splices
 *       the wire {@code command} field on, and pushes through the connection.
 * </ul>
 *
 * <p>See {@code documentation/research/lobby-protocol-spec.md} §§1, 8 for the wire format and
 * heartbeat, and §10 for the inbound/outbound payload catalog.
 */
package com.faforever.testharness.client.lobby;
