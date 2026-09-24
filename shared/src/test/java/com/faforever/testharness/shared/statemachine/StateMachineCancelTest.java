package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
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

    @Test
    void cancelLeavesAPendingStateReachedFutureIncomplete() {
        // Pins the documented gap on cancel(): it does not resolve stateReached() futures. See the
        // javadoc on cancel() (WBS-2.3.7-fix, #260) for why this is deliberate rather than an
        // oversight — an earlier attempt to resolve these futures here regressed the ordinary
        // shutdown path.
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        CompletableFuture<Void> awaiting = machine.stateReached(c);

        machine.cancel();

        assertFalse(
                awaiting.isDone(),
                "cancel() must not resolve a stateReached() future for a state it never reaches");
    }
}
