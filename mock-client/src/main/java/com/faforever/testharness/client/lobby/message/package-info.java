/**
 * Typed record types for the FAF lobby protocol's command-bearing JSON frames. Each record is
 * annotated with {@link com.faforever.testharness.client.lobby.message.LobbyCommand} carrying its
 * wire {@code command} string; {@link
 * com.faforever.testharness.client.lobby.message.InboundMessage} and {@link
 * com.faforever.testharness.client.lobby.message.OutboundMessage} are the sealed catalogues the
 * dispatcher and sender consume.
 *
 * <p>Wire schemas live in {@code documentation/research/lobby-protocol-spec.md} §10. Adding a new
 * record requires (a) declaring the record here, (b) extending the appropriate {@code permits}
 * clause, and (c) registering it with {@link
 * com.faforever.testharness.client.lobby.LobbyMessageDispatcher} (inbound only).
 */
package com.faforever.testharness.client.lobby.message;
