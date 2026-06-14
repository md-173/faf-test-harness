/**
 * WebSocket transport for the FAF lobby protocol.
 *
 * <ul>
 *   <li>{@link com.faforever.testharness.client.lobby.LobbyConnection} — raw-frame WebSocket
 *       transport (3.1.1.1): JSON encode/decode, automatic pong, multi-handler-per-command routing.
 *   <li>{@link com.faforever.testharness.client.lobby.LobbyMessageHandler} — JsonNode-level handler
 *       interface registered with the connection.
 *   <li>{@link com.faforever.testharness.client.lobby.JsonRequire} — small helper for pulling
 *       required fields off a {@link com.fasterxml.jackson.databind.JsonNode}, for consumers that
 *       read a couple of fields without going through a typed record.
 * </ul>
 *
 * <p>Two typed records live in the {@link com.faforever.testharness.client.lobby.message}
 * subpackage for the payloads complex enough to warrant one — {@link
 * com.faforever.testharness.client.lobby.message.WelcomeMessage} and {@link
 * com.faforever.testharness.client.lobby.message.GameLaunchMessage}. Consumers decode them with
 * {@code mapper.treeToValue(node, WelcomeMessage.class)}; everything else uses JsonNode + {@link
 * com.faforever.testharness.client.lobby.JsonRequire}.
 *
 * <p>See {@code documentation/research/lobby-protocol-spec.md} §§1, 8 for the wire format and
 * heartbeat, §3 / §5 / §10 for the payload catalog.
 */
package com.faforever.testharness.client.lobby;
