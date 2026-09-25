package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;

/**
 * The lobby sent a {@code game_launch} this client cannot use (WBS-3.1.1.6-fix, #457).
 *
 * @param reason one line naming the field and why it was refused
 */
/*package-private*/ record LaunchRejected(String reason) implements Event {}
