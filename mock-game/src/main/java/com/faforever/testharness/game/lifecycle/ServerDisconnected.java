package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * The GPGNet server has disconnected from the mock game.
 *
 * @param reason the reason for the disconnection.
 */
/*package-private*/ record ServerDisconnected(DisconnectReason reason) implements Event {}
