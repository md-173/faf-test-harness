package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/** GPGNet server instructed the mock game to be the host. */
public record HostGame() implements Event {}
