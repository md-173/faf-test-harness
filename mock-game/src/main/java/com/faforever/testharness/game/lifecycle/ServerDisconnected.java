package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/** The GPGNet server has disconnected from the mock game. */
/*package-private*/ record ServerDisconnected() implements Event {}
