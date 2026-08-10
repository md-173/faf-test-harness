package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * Instructed to create a lobby by the GPGNet server.
 *
 * @param frame The GpgNet frame that produced this event.
 */
/*package-private*/ record CreateLobby(GpgNetFrame frame) implements Event {}
