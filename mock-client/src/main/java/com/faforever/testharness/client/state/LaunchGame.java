package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby instructs mock client to launch game process.
 *
 * @param command the launch_game command received.
 */
/*package-private*/ record LaunchGame(JsonNode command) implements Event {}
