/**
 * Mock game lifecycle: startup and teardown wiring. {@link
 * com.faforever.testharness.game.lifecycle.GameShutdown} is the single idempotent shutdown sequence
 * (stop FSM scheduling, close the GPGNet connection, flush/stop logging) shared by the FSM's
 * self-initiated exit and the JVM shutdown hook.
 */
package com.faforever.testharness.game.lifecycle;
