package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby instructs mock client to host a game for other peers to join.
 *
 * @param command the HostGame command received.
 */
/*package-private*/ record HostGame(JsonNode command) implements Event {}
