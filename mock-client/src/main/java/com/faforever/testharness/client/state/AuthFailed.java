package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;

/**
 * Authentication has failed during the handshake with the lobby server.
 *
 * @param cause the exception that caused this failure.
 */
/*package-private*/ record AuthFailed(Throwable cause) implements Event {}
