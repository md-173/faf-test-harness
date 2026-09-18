package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateMachine#cancel()} — the terminal stop of time-based scheduling used by the
 * shutdown path.
 */
final class StateMachineCancelTest {

    private final class Shutdown implements Event {}

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
    void cancelReleasesAPendingStateReachedFuture() {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        CompletableFuture<Void> awaiting = machine.stateReached(c);

        machine.cancel();

        assertTrue(
                awaiting.isCancelled(),
                "cancel() must release a future awaiting an unreached state");
        assertThrows(CancellationException.class, awaiting::join);
    }

    @Test
    void cancelLeavesAnAlreadyReachedStateFutureAlone() {
        State a = new State("A");
        StateMachine machine = new StateMachine(a);
        CompletableFuture<Void> awaiting = machine.stateReached(a);

        machine.cancel();

        assertFalse(
                awaiting.isCancelled(),
                "a future for the current state is already complete, not cancelled");
        assertDoesNotThrow(awaiting::join);
    }

    @Test
    void secondCancelIsSafeAfterAwaitedFuturesWereReleased() {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        machine.stateReached(c);

        machine.cancel();
        assertDoesNotThrow(
                machine::cancel, "a second cancel() must be safe once awaiters were released");
    }

    @Test
    void cancelCalledReentrantlyFromAnEntryHookLeavesThatStatesFutureAlone() throws Exception {
        // Mirrors GameShutdown: its ENDED entry hook calls fsm.cancel() before the machine has
        // actually committed to ENDED, so a future taken on ENDED beforehand must still complete
        // normally rather than being swept up by that reentrant cancel().
        State a = new State("A");
        State ended = new State("ENDED");
        a.registerTransition(Shutdown.class, ended);
        StateMachine machine = new StateMachine(a);
        ended.onEntry(machine::cancel);

        CompletableFuture<Void> reachedEnded = machine.stateReached(ended);
        machine.receiveEvent(new Shutdown());

        assertSame(ended, machine.getState());
        assertFalse(
                reachedEnded.isCancelled(),
                "the future for the state whose entry hook called cancel() must still complete");
        assertDoesNotThrow(() -> reachedEnded.get(1, TimeUnit.SECONDS));
    }
}
