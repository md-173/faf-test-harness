package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby instructs mock client to join a game another peer started.
 *
 * @param command the JoinGame command received.
 */
/*package-private*/ record JoinGame(JsonNode command) implements Event {}
