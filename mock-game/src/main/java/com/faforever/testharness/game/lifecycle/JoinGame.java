package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * GPGNet server instructed the mock game to be a joining peer.
 *
 * @param frame The GpgNet frame that produced this event.
 */
/*package-private*/ record JoinGame(GpgNetFrame frame) implements Event {}
