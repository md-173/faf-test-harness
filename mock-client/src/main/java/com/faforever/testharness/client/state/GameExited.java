package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;

/**
 * Game binary has exited.
 *
 * @param exitCode the numeric code with which the binary exited.
 */
/*package-private*/ record GameExited(int exitCode) implements Event {}
