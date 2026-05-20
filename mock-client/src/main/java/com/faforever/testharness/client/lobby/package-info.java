/**
 * WebSocket transport for the FAF lobby protocol: the {@link
 * com.faforever.testharness.client.lobby.LobbyConnection} class plus the {@link
 * com.faforever.testharness.client.lobby.LobbyMessageHandler} interface every higher-level
 * lobby/matchmaking module registers against. See {@code
 * documentation/research/lobby-protocol-spec.md} §§1, 8 for the wire format and heartbeat.
 */
package com.faforever.testharness.client.lobby;
