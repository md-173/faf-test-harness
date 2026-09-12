package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby tells the mock client that a player has left, so its adapter should drop that peer
 * (WBS-4.3.4).
 *
 * <p>Source-verified against faf-server: when a game connection aborts, {@code
 * GameConnection.abort} calls {@code disconnect_all_peers()} guarded by {@code if self.game.state
 * is GameState.LOBBY}, and that sends every <em>other</em> connection {@code
 * send_DisconnectFromPeer(departing_player_id)}. The envelope is the usual GPGNet-over-WebSocket
 * one, {@code {command, target: "game", args: [player_id]}}, built by {@code send_gpgnet_message}
 * with the target added by {@code GameConnection.send}.
 *
 * <p>The LOBBY guard is why this is a lobby-phase signal only. After the host reports {@code
 * GameState Launching} the server sends no targeted departure notice at all, and a survivor learns
 * of a departure from its own adapter instead, roughly ten seconds later. See the runbook's
 * multi-peer limitations.
 *
 * @param command the DisconnectFromPeer command received.
 */
/*package-private*/ record DisconnectFromPeer(JsonNode command) implements Event {}
