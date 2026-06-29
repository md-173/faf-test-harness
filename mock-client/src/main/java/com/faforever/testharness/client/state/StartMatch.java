package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;

/** Event that instructs the mock client to start the match. */
/*package-private*/ record StartMatch() implements Event {}
