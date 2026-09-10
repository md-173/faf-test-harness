package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
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

    @Test
    void waitsUntilStateReached() throws Exception {
        State red = new State("RED");
        State green = new State("GREEN");

        red.registerTransition(IncomingCarDetected.class, green);
        StateMachine machine = new StateMachine(red);

        // Taken before the transition is scheduled. stateReached returns an already-completed
        // future for the current state and registers one otherwise, both under the machine's own
        // monitor, so a future taken up front is released whenever the commit happens — however
        // early the timer fires. Taken afterwards it would race the transition it is waiting for.
        CompletableFuture<Void> reachedGreen = machine.stateReached(green);

        Timer timer = new Timer();
        try {
            timer.schedule(
                    new TimerTask() {
                        @Override
                        public void run() {
                            machine.receiveEvent(new IncomingCarDetected());
                        }
                    },
                    TRANSITION_DELAY_MS);

            reachedGreen.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            // The timer thread outlives this method otherwise, and a leaked thread logging into
            // another test's captured output is its own class of flake.
            timer.cancel();
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
}
