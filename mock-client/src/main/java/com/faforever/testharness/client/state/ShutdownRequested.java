package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;

/** A manual shutdown of the mock client has been requested. */
/*package-private*/ record ShutdownRequested() implements Event {}
