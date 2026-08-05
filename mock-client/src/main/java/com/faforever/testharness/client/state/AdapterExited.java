package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;

/**
 * ICE adapter process has exited (#214).
 *
 * @param exitCode the numeric code with which the adapter process exited.
 */
/*package-private*/ record AdapterExited(int exitCode) implements Event {}
