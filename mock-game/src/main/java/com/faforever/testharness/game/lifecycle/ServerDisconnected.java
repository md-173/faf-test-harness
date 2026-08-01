package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/** The GPGNet server has disconnected from the mock game. */
public record ServerDisconnected() implements Event {}
