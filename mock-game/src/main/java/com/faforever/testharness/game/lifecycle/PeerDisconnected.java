package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/** A peer has disconnected from the game. */
public record PeerDisconnected() implements Event {}
