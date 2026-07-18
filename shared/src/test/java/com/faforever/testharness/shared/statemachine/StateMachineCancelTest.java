package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateMachine#cancel()} — the terminal stop of time-based scheduling used by the
 * shutdown path.
 */
final class StateMachineCancelTest {

    @Test
    void cancelStopsPendingTimeoutFromFiring() throws Exception {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        machine.setTimeout(150, c);

        machine.cancel();

        Thread.sleep(300); // well past the 150ms timeout — it must not have fired
        assertSame(a, machine.getState(), "cancel() should stop the scheduled timeout");
    }

    @Test
    void cancelIsIdempotent() {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        machine.setTimeout(150, c);

        machine.cancel();
        assertDoesNotThrow(machine::cancel, "a second cancel() must be safe");
    }

    @Test
    void cancelWithNoPendingTimeoutsIsSafe() {
        StateMachine machine = new StateMachine(new State("A"));
        assertDoesNotThrow(machine::cancel, "cancel() on a machine that never scheduled is safe");
    }
}
