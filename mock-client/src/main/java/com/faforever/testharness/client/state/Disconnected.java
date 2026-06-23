package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * Mock client has lost connection to lobby.
 *
 * @param event the event that caused disconnetion
 */
/*package-private*/ record Disconnected(LobbyConnection.DisconnectEvent event) implements Event {}
