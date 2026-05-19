package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Test the timeout feature in the {@ref StateMachine} class. This feature allows the creation of
 * timeouts that automatically change the state if no other changes occur within a given timeframe.
 */
final class StateMachineTimeoutTest {
    private final class AToB implements Event {}

    @Test
    void timeoutWorks() {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");
        Event aToB = new AToB();

        a.registerTransition(AToB.class, b);
        StateMachine machine = new StateMachine(a);
        assertTrue(machine.getState() == a);

        machine.setTimeout(200, c);
        try {
            Thread.sleep(300); // 0.3 seconds to ensure timeout has time to trigger.
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }
        assertTrue(machine.getState() == c);
    }

    @Test
    void timeoutGetsCancelled() {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");
        Event aToB = new AToB();

        a.registerTransition(AToB.class, b);
        StateMachine machine = new StateMachine(a);
        assertTrue(machine.getState() == a);

        machine.setTimeout(200, c);
        try {
            Thread.sleep(100); // 0.1 second, not enough time for timeout.
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }

        // Should transition to B
        machine.receiveEvent(aToB);

        try {
            Thread.sleep(300); // 0.3 second to ensure timeout would have triggered.
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }

        assertTrue(machine.getState() == b);
    }

    /**
     * Regression test for a bug in which the timer itself gets cancelled, resulting in further
     * usages raising exceptions.
     */
    @Test
    void timeoutReusable() {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");
        Event aToB = new AToB();

        a.registerTransition(AToB.class, b);
        StateMachine machine = new StateMachine(a);
        assertTrue(machine.getState() == a);

        machine.setTimeout(200, c);

        // Timeout gets cancelled here, as shown by the previous test.
        machine.receiveEvent(aToB);
        assertTrue(machine.getState() == b);

        machine.setTimeout(200, a);
        try {
            Thread.sleep(300); // 0.3 seconds to ensure timeout has time to trigger.
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }

        // Second timeout should have occured.
        assertTrue(machine.getState() == a);
    }

    @Test
    void timeoutCancelsOtherTimeouts() {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        StateMachine machine = new StateMachine(a);
        assertTrue(machine.getState() == a);

        // Two timeouts, the one to b should execute first and cancel the one to c.
        machine.setTimeout(100, b);
        machine.setTimeout(200, c);

        try {
            Thread.sleep(300); // 0.3 seconds to ensure timeout has time to trigger.
        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        }

        assertTrue(machine.getState() == b);
    }
}
