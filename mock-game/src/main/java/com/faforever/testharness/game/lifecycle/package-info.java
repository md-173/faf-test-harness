/**
 * Mock game lifecycle: startup and teardown wiring. {@link
 * com.faforever.testharness.game.lifecycle.GameShutdown} is the single idempotent shutdown sequence
 * (close the GPGNet connection, then stop FSM scheduling) shared by the FSM's self-initiated exit
 * and the JVM shutdown hook. Stopping the logging context is deliberately not part of it; the
 * bootstrap owns that step (WBS-3.2.5.1).
 */
package com.faforever.testharness.game.lifecycle;
