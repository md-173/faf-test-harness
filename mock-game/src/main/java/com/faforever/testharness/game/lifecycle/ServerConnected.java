package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/** Indicates that a successful connection with the server has been established. */
public record ServerConnected() implements Event {}
