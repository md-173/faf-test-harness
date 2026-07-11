package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * Lobby instructs mock client to launch game process.
 *
 * @param config the configuration settings for the game.
 */
/*package-private*/ record LaunchGame(GameConfig config) implements Event {}
