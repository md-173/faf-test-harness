package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class StateMachineStateWaitTest {

    /**
     * Upper bound on the wait, not an expectation of how long it takes. The transition below lands
     * in half a second on an idle machine; this suite shares a forked JVM with tests that spawn
     * real child processes, and the observed CI failure was the timer thread running roughly half a
     * second late against a budget only half a second wide. A bound this generous costs nothing
     * when nothing is contended and keeps the test deterministic when something is.
     */
    private static final int AWAIT_SECONDS = 5;

    /** How long after arming before the transition is fired. */
    private static final long TRANSITION_DELAY_MS = 500;

    private final class IncomingCarDetected implements Event {}

    private final class IncomingCarLeft implements Event {}

    @Test
    void waitsUntilStateReached() throws Exception {
        State red = new State("RED");
        State green = new State("GREEN");

        red.registerTransition(IncomingCarDetected.class, green);
        StateMachine machine = new StateMachine(red);

        // Taken before the transition is scheduled. stateReached returns an already-completed
        // future for the current state and registers one otherwise, both under the machine's own
        // monitor, so a future taken up front is released whenever the commit happens — however
        // early the timer fires. Taken afterwards it would still be correct here, since green is
        // terminal, but the habit matters for any state the machine can pass through and leave: a
        // future registered after that has happened misses the visit and completes only on a later
        // entry, if one ever comes.
        CompletableFuture<Void> reachedGreen = machine.stateReached(green);

        // Timed on the monotonic clock, as the machine's own timeouts are (WBS-2.3.7-fix, #465).
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            timer.schedule(
                    () -> machine.receiveEvent(new IncomingCarDetected()),
                    TRANSITION_DELAY_MS,
                    TimeUnit.MILLISECONDS);

            reachedGreen.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            // The timer thread outlives this method otherwise, and a leaked thread logging into
            // another test's captured output is its own class of flake. shutdownNow() also drops
            // the event if the wait gave up before it was sent, which shutdown() would still send.
            timer.shutdownNow();
        }

        assertSame(green, machine.getState());
    }

    @Test
    void immediateCompletionWhenSameState() throws Exception {
        State red = new State("RED");
        State green = new State("GREEN");

        red.registerTransition(IncomingCarDetected.class, green);
        StateMachine machine = new StateMachine(red);

        machine.receiveEvent(new IncomingCarDetected());
        machine.stateReached(green).get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * The wait is edge triggered (WBS-2.3.7-fix, #250): a future taken after the machine has
     * entered and left a state does not see that visit, and completes only on the next entry.
     */
    @Test
    void aStateAlreadyLeftIsObservedOnlyOnItsNextEntry() {
        State red = new State("RED");
        State green = new State("GREEN");
        red.registerTransition(IncomingCarDetected.class, green);
        green.registerTransition(IncomingCarLeft.class, red);
        StateMachine machine = new StateMachine(red);

        machine.receiveEvent(new IncomingCarDetected());
        machine.receiveEvent(new IncomingCarLeft());
        CompletableFuture<Void> reachedGreen = machine.stateReached(green);

        assertFalse(reachedGreen.isDone(), "GREEN was entered and left before the wait was taken");
        machine.receiveEvent(new IncomingCarDetected());
        assertTrue(reachedGreen.isDone(), "the wait completes on the next entry to GREEN");
    }
}
