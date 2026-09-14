package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
 * <p>{@link #timeoutGetsCancelled()} still sleeps, deliberately: there is nothing to await for an
 * event that must never arrive. That is not the same as being load-proof. Its first sleep is an
 * upper bound rather than a lower one, because the event has to beat the timeout armed before it,
 * and if that sleep overshoots, the machine is already in C and the IGNORE policy drops the event.
 * Fixing that needs a different technique, tracked separately.
 */
final class StateMachineTimeoutTest {

    /** Upper bound on every await here; see the class javadoc. */
    private static final int AWAIT_SECONDS = 5;

    /** The timeout under test in most cases below. */
    private static final long TIMEOUT_MS = 200;

    /** The earlier of the two timeouts in {@link #timeoutCancelsOtherTimeouts()}. */
    private static final long EARLIER_TIMEOUT_MS = 100;

    /** Armed only to be cancelled, so long enough that it can never win the race. */
    private static final long CANCELLED_TIMEOUT_MS = 10_000;

    private final class AToB implements Event {}

    /** Every machine a test built, so {@link #stopTimers()} can shut its timer thread down. */
    private final List<StateMachine> machines = new ArrayList<>();

    /**
     * Stops each machine's scheduling. Without this every test leaves a live daemon timer thread
     * behind, and one of them leaves an armed task that logs into a later test's captured output —
     * the same leak {@code StateMachineStateWaitTest} cancels its own {@link java.util.Timer} for.
     */
    @AfterEach
    void stopTimers() {
        machines.forEach(StateMachine::cancel);
        machines.clear();
    }

    /**
     * Builds a machine and registers it for {@link #stopTimers()}.
     *
     * @param initial the machine's starting state
     * @return the tracked machine
     */
    private StateMachine machineFrom(State initial) {
        StateMachine machine = new StateMachine(initial);
        machines.add(machine);
        return machine;
    }

    @Test
    void timeoutWorks() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        a.registerTransition(AToB.class, b);
        StateMachine machine = machineFrom(a);
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
        StateMachine machine = machineFrom(a);
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
        StateMachine machine = machineFrom(a);
        assertSame(a, machine.getState());

        // Long enough that it cannot fire before the event below cancels it: if it did, the
        // machine would be in C, which has no transition for AToB, and the IGNORE policy would
        // drop the event. The machine's Timer is a daemon, so nothing ever waits this out.
        machine.setTimeout(CANCELLED_TIMEOUT_MS, c);

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

        StateMachine machine = machineFrom(a);
        assertSame(a, machine.getState());

        // Two timeouts, the one to b should execute first and cancel the one to c. Armed under
        // the machine's own monitor so the timer thread cannot commit into b partway through: if
        // it did, the timeout into c would be armed against an already-cleared list, nothing would
        // cancel it, and both assertions below would still hold while meaning nothing.
        CompletableFuture<Void> reachedC;
        synchronized (machine) {
            machine.setTimeout(EARLIER_TIMEOUT_MS, b);
            reachedC = machine.stateReached(c);
            machine.setTimeout(TIMEOUT_MS, c);
        }

        machine.stateReached(b).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertSame(b, machine.getState());
        // Not a race against the wall clock: the machine schedules both tasks on one Timer thread,
        // so b's runs to completion — cancelling c's before it can start — and the assertion is
        // settled by the time the await above returns, whenever that is.
        assertFalse(reachedC.isDone(), "reaching b must have cancelled the pending timeout into c");
    }
}
