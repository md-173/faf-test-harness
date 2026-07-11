package com.faforever.testharness.shared.statemachine;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class StateMachineStateWaitTest {
    private final class IncomingCarDetected implements Event {}

    @Test
    void waitsUntilStateReached() throws Exception {
        State red = new State("RED");
        State green = new State("GREEN");

        red.registerTransition(IncomingCarDetected.class, green);
        StateMachine machine = new StateMachine(red);

        Timer timer = new Timer();
        // Schedule the transition for 500 milliseconds from now, then wait 1000 milliseconds before
        // failing the test.
        timer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        machine.receiveEvent(new IncomingCarDetected());
                    }
                },
                500);
        machine.stateReached(green).get(1, TimeUnit.SECONDS);
    }

    @Test
    void immediateCompletionWhenSameState() throws Exception {
        State red = new State("RED");
        State green = new State("GREEN");

        red.registerTransition(IncomingCarDetected.class, green);
        StateMachine machine = new StateMachine(red);

        machine.receiveEvent(new IncomingCarDetected());
        machine.stateReached(green).get(1, TimeUnit.SECONDS);
    }
}
