package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/** Instructed to create a lobby by the GPGNet server. */
public record CreateLobby() implements Event {}
