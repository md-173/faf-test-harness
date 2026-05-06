package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Ensures state machine transitions correctly. Uses a traffic light toy example.
 *
 * <p>Tests get progressively more complicated to show additional features.
 */
@SuppressWarnings("WhitespaceAround")
final class StateMachineTransitionsTest {
    private final class IncomingCarDetected extends Event {}

    private final class IncomingCarLeft extends Event {}

    private final class CrossingCarDetected extends Event {}

    private final class CrossingCarLeft extends Event {}

    private boolean actionHappened = false;
    private int carsCrossing;

    @Test
    void simpleTransitions() {
        State red = new State("RED");
        State amber = new State("AMBER");
        State green = new State("GREEN");
        Event incomingCar = new IncomingCarDetected();
        Event carLeft = new CrossingCarLeft();

        red.registerTransition(incomingCar, amber);
        amber.registerTransition(carLeft, green);
        StateMachine machine = new StateMachine(red);
        assertTrue(machine.getState() == red);

        // Changes to amber.
        machine.receiveEvent(incomingCar);
        assertTrue(machine.getState() == amber);

        // Stays amber.
        machine.receiveEvent(incomingCar);
        assertTrue(machine.getState() == amber);

        // Changes to green.
        machine.receiveEvent(carLeft);
        assertTrue(machine.getState() == green);

        // Stays green.
        machine.receiveEvent(incomingCar);
        assertTrue(machine.getState() == green);
    }

    @Test
    void withAction() {
        State red = new State("RED");
        State green = new State("GREEN");
        Event event = new IncomingCarDetected();

        actionHappened = false;

        red.registerTransition(event, green, () -> actionHappened = true, null);
        StateMachine machine = new StateMachine(red);
        assertTrue(machine.getState() == red);
        assertTrue(!actionHappened);

        // Changes to green and performs action with side-effect.
        machine.receiveEvent(event);
        assertTrue(machine.getState() == green);
        assertTrue(actionHappened);
    }

    @Test
    void withGuard() {
        State green = new State("GREEN");
        State amber = new State("AMBER");
        State red = new State("RED");

        Event crossingCarWaiting = new CrossingCarDetected();
        Event incomingCarLeft = new IncomingCarLeft();

        // Light turns amber when a car comes from intersecting road.
        green.registerTransition(crossingCarWaiting, amber);
        // Turn from amber to red only if there's no cars currently in the road.
        amber.registerTransition(incomingCarLeft, red, null, () -> carsCrossing == 0);

        StateMachine machine = new StateMachine(green);

        carsCrossing = 2;

        machine.receiveEvent(crossingCarWaiting);
        assertTrue(machine.getState() == amber);

        // Still amber. carsCrossing > 0
        machine.receiveEvent(incomingCarLeft);
        assertTrue(machine.getState() == amber);

        // Now red.
        carsCrossing = 0;
        machine.receiveEvent(incomingCarLeft);
        assertTrue(machine.getState() == red);
    }
}
