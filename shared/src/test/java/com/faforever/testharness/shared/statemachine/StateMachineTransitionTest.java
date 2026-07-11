package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Ensures state machine transitions correctly. Uses a traffic light toy example.
 *
 * <p>Tests get progressively more complicated to show additional features.
 */
final class StateMachineTransitionTest {
    private final class IncomingCarDetected implements Event {}

    private final class IncomingCarLeft implements Event {}

    private final class CrossingCarDetected implements Event {}

    private final class CrossingCarLeft implements Event {}

    private int carsCrossing;

    @Test
    void simpleTransitions() {
        State red = new State("RED");
        State amber = new State("AMBER");
        State green = new State("GREEN");
        Event incomingCar = new IncomingCarDetected();
        Event carLeft = new CrossingCarLeft();

        red.registerTransition(IncomingCarDetected.class, amber);
        amber.registerTransition(CrossingCarLeft.class, green);
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

        CountDownLatch actionHappened = new CountDownLatch(1);

        red.registerTransition(
                IncomingCarDetected.class, green, ignored -> actionHappened.countDown(), null);
        StateMachine machine = new StateMachine(red);
        assertTrue(machine.getState() == red);
        assertTrue(actionHappened.getCount() == 1);

        // Changes to green and performs action with side-effect.
        machine.receiveEvent(new IncomingCarDetected());
        assertTrue(machine.getState() == green);
        assertTrue(actionHappened.getCount() == 0);
    }

    @Test
    void actionWithFailure() {
        State red = new State("RED");
        State green = new State("GREEN");
        State fail = new State("FAIL");

        CountDownLatch failureEntryHook = new CountDownLatch(1);
        fail.onEntry(failureEntryHook::countDown);

        red.registerTransition(
                IncomingCarDetected.class,
                green,
                ignored -> {
                    throw new FailedTransitionException("FAILED", fail);
                },
                null);
        StateMachine machine = new StateMachine(red);
        assertTrue(machine.getState() == red);

        // Changes to green and performs action with side-effect.
        machine.receiveEvent(new IncomingCarDetected());
        assertTrue(machine.getState() == fail);
        assertTrue(failureEntryHook.getCount() == 0);
    }

    @Test
    void withGuard() {
        State green = new State("GREEN");
        State amber = new State("AMBER");
        State red = new State("RED");

        Event crossingCarWaiting = new CrossingCarDetected();
        Event incomingCarLeft = new IncomingCarLeft();

        // Light turns amber when a car comes from intersecting road.
        green.registerTransition(CrossingCarDetected.class, amber);
        // Turn from amber to red only if there's no cars currently in the road.
        amber.registerTransition(IncomingCarLeft.class, red, null, (e) -> carsCrossing == 0);

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

    @Test
    void withHooks() {
        State red = new State("RED");
        State green = new State("GREEN");
        // Messages get pushed here to confirm order of different runnables.
        List<String> processLog = new ArrayList<>();

        red.registerTransition(
                IncomingCarDetected.class, green, ignored -> processLog.add("action"), null);
        red.onExit(() -> processLog.add("red exit"));
        green.onEntry(() -> processLog.add("green entry"));

        StateMachine machine = new StateMachine(red);
        assertTrue(machine.getState() == red);

        // Changes to green and performs action and hooks with side-effect.
        machine.receiveEvent(new IncomingCarDetected());
        assertTrue(machine.getState() == green);
        assertEquals(List.of("action", "red exit", "green entry"), processLog);
    }

    @Test
    void ignoreInvalidTransition() {
        State red = new State("RED");
        State amber = new State("AMBER");
        State green = new State("GREEN");

        red.registerTransition(IncomingCarDetected.class, amber);
        amber.registerTransition(CrossingCarLeft.class, green);

        Event incomingCar = new IncomingCarDetected();
        Event carLeft = new CrossingCarLeft();

        // By default, policy is ignore
        StateMachine machine = new StateMachine(red);
        assertTrue(machine.getState() == red);

        // Invalid transition
        machine.receiveEvent(carLeft);
        // Transition ignored, still red
        assertTrue(machine.getState() == red);
    }

    @Test
    void throwOnInvalidTransition() {
        State green = new State("GREEN");
        State amber = new State("AMBER");
        State red = new State("RED");

        // Light turns amber when a car comes from intersecting road.
        green.registerTransition(CrossingCarDetected.class, amber);
        // Turn from amber to red only if there's no cars currently in the road.
        amber.registerTransition(IncomingCarLeft.class, red, null, (e) -> carsCrossing == 0);

        Event crossingCarWaiting = new CrossingCarDetected();
        Event incomingCarLeft = new IncomingCarLeft();

        StateMachine machine = new StateMachine(green, InvalidTransitionPolicy.THROW);

        InvalidTransitionException e =
                assertThrows(
                        InvalidTransitionException.class,
                        () -> machine.receiveEvent(incomingCarLeft),
                        "Expected exception to be thrown");

        assertTrue(e.getMessage().contains("IncomingCarLeft"));

        machine.receiveEvent(crossingCarWaiting);
        assertTrue(machine.getState() == amber);

        carsCrossing = 2;
        // Still amber. carsCrossing > 0
        // No exception thrown here, transition is stopped by a guard
        machine.receiveEvent(incomingCarLeft);
        assertTrue(machine.getState() == amber);

        // Now red.
        carsCrossing = 0;
        machine.receiveEvent(incomingCarLeft);
        assertTrue(machine.getState() == red);
    }
}
