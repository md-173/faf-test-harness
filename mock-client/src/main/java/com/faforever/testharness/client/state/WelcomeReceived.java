package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * Authentication with the lobby server was successful.
 *
 * @param state the session information given by the lobby server.
 */
/*package-private*/ record WelcomeReceived(SessionState state) implements Event {}
