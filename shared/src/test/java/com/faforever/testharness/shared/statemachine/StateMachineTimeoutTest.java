package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Test the timeout feature in the {@link StateMachine} class. This feature allows the creation of
 * timeouts that automatically change the state if no other changes occur within a given timeframe.
 *
 * <p>The cases that assert a timeout <em>did</em> fire await the state rather than sleeping past
 * the deadline (#247). A sleep asserts that the work finished inside a fixed wall-clock margin,
 * which is a claim about the machine the test runs on: this suite shares a forked JVM with tests
 * that spawn real child processes, and a 100ms margin does not survive that. Awaiting the state
 * keeps the same guarantee — determinism comes from the budget being an upper bound rather than an
 * expectation.
 *
 * <p>{@link #timeoutGetsCancelled()} still sleeps, deliberately. It asserts a timeout did
 * <em>not</em> fire, so load makes it more reliable rather than less, and there is nothing to await
 * for an event that must never arrive.
 */
final class StateMachineTimeoutTest {

    /** Upper bound on every await here; see the class javadoc. */
    private static final int AWAIT_SECONDS = 5;

    /** The timeout under test in most cases below. */
    private static final long TIMEOUT_MS = 200;

    /** The earlier of the two timeouts in {@link #timeoutCancelsOtherTimeouts()}. */
    private static final long EARLIER_TIMEOUT_MS = 100;

    private final class AToB implements Event {}

    @Test
    void timeoutWorks() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        a.registerTransition(AToB.class, b);
        StateMachine machine = new StateMachine(a);
        assertSame(a, machine.getState());

        machine.setTimeout(TIMEOUT_MS, c);
        machine.stateReached(c).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertSame(c, machine.getState());
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
    void timeoutReusable() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");
        Event aToB = new AToB();

        a.registerTransition(AToB.class, b);
        StateMachine machine = new StateMachine(a);
        assertSame(a, machine.getState());

        machine.setTimeout(TIMEOUT_MS, c);

        // Timeout gets cancelled here, as shown by the previous test.
        machine.receiveEvent(aToB);
        assertSame(b, machine.getState());

        machine.setTimeout(TIMEOUT_MS, a);
        machine.stateReached(a).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Second timeout should have occurred, so the timer survived the first being cancelled.
        assertSame(a, machine.getState());
    }

    @Test
    void timeoutCancelsOtherTimeouts() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        StateMachine machine = new StateMachine(a);
        assertSame(a, machine.getState());

        // Two timeouts, the one to b should execute first and cancel the one to c.
        machine.setTimeout(EARLIER_TIMEOUT_MS, b);
        // Registered before the later timeout is armed, so it cannot miss a commit into c.
        CompletableFuture<Void> reachedC = machine.stateReached(c);
        machine.setTimeout(TIMEOUT_MS, c);

        machine.stateReached(b).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertSame(b, machine.getState());
        // Not a race against the wall clock: the machine schedules both tasks on one Timer thread,
        // so b's runs to completion — cancelling c's before it can start — and the assertion is
        // settled by the time the await above returns, whenever that is.
        assertFalse(reachedC.isDone(), "reaching b must have cancelled the pending timeout into c");
    }
}
