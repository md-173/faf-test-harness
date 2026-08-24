package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests the two ways a scheduled timeout can misbehave once it has already been handed to the timer
 * thread: firing after it was cancelled, and throwing something other than {@link
 * FailedTransitionException}.
 */
final class StateMachineTimeoutRobustnessTest {
    private static final long TIMEOUT_MS = 100;
    private static final long SETTLE_MS = 300;
    private static final int AWAIT_SECONDS = 2;

    private final class Go implements Event {}

    /**
     * {@link java.util.TimerTask#cancel()} cannot stop a task the timer thread has already
     * dequeued. Such a task blocks on the machine's monitor and, once released, used to commit a
     * transition out of a state the machine had already left.
     */
    @Test
    void timeoutCancelledAfterBeingDequeuedDoesNotCommit() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State doom = new State("DOOM");

        a.registerTransition(Go.class, b);
        StateMachine machine = new StateMachine(a);
        machine.setTimeout(TIMEOUT_MS, doom);

        // Take the monitor before the timeout is due, so the timer thread dequeues the task and
        // then parks inside run() waiting for us — the window cancel() cannot close.
        synchronized (machine) {
            Thread.sleep(SETTLE_MS);
            machine.receiveEvent(new Go());
            assertSame(b, machine.getState());
        }

        // The released task must notice it is no longer pending and do nothing.
        Thread.sleep(SETTLE_MS);
        assertSame(b, machine.getState(), "a cancelled timeout must not commit a transition");
    }

    /**
     * A runtime exception thrown by a timeout action used to escape {@code run()} and kill the
     * timer thread, after which every later {@link StateMachine#setTimeout(long, State)} threw.
     */
    @Test
    void runtimeExceptionInTimeoutActionLeavesTimerUsable() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        StateMachine machine = new StateMachine(a);
        machine.setTimeout(
                TIMEOUT_MS,
                b,
                ignored -> {
                    throw new IllegalStateException("action blew up");
                });

        Thread.sleep(SETTLE_MS);
        assertSame(a, machine.getState(), "a throwing action must not move the machine");

        var reachedC = machine.stateReached(c);
        assertDoesNotThrow(
                () -> machine.setTimeout(TIMEOUT_MS, c),
                "the timer must survive a throwing action");
        reachedC.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(c, machine.getState());
    }
}
