package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>The cases that assert a timeout did <em>not</em> fire still sleep past its deadline,
 * deliberately: there is nothing to await for a timeout that must never fire, and it is only
 * observable once its deadline has passed. Load cannot fail them either. Each arms its timeouts and
 * does what must beat them under the machine's own monitor, which {@code UpdateStateTask.run} also
 * takes, so the timer thread cannot commit in between however late the test thread runs (#380).
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

    private final class BToC implements Event {}

    /** Every machine a test built, so {@link #stopTimers()} can shut its timer thread down. */
    private final List<StateMachine> machines = new ArrayList<>();

    /**
     * Stops each machine's scheduling. Without this every test leaves a live daemon timer thread
     * behind, and one of them leaves an armed task that logs into a later test's captured output:
     * the same leak {@code StateMachineStateWaitTest} shuts its own scheduler down for.
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
    void timeoutGetsCancelled() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        a.registerTransition(AToB.class, b);
        StateMachine machine = machineFrom(a);
        assertSame(a, machine.getState());

        // Armed and beaten under the machine's monitor; see the class javadoc.
        synchronized (machine) {
            machine.setTimeout(TIMEOUT_MS, c);
            machine.receiveEvent(new AToB());
        }

        Thread.sleep(TIMEOUT_MS * 2); // past the deadline: a timeout still armed would have fired
        assertSame(
                b, machine.getState(), "the event into b must have cancelled the timeout into c");
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
        // drop the event. Cancelling it drops it from the machine's queue, so nothing ever waits
        // this out.
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
        // it did, the timeout into c would be armed after b's commit, nothing would cancel it, and
        // the test would fail spuriously.
        CompletableFuture<Void> reachedC;
        synchronized (machine) {
            machine.setTimeout(EARLIER_TIMEOUT_MS, b);
            reachedC = machine.stateReached(c);
            machine.setTimeout(TIMEOUT_MS, c);
        }

        machine.stateReached(b).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(b, machine.getState());

        // A timeout that must not fire is only observable after its deadline: the moment b is
        // reached, reachedC is incomplete whether or not c's timeout was cancelled (#381).
        Thread.sleep(TIMEOUT_MS * 2);
        assertFalse(reachedC.isDone(), "reaching b must have cancelled the pending timeout into c");
    }

    /**
     * A timeout armed by a transition's own action belongs to the state that transition moves into,
     * so the transition's commit keeps it (#259).
     */
    @Test
    void aTimeoutArmedInATransitionActionSurvivesItsCommit() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State later = new State("LATER");
        StateMachine machine = machineFrom(a);
        a.registerTransition(AToB.class, b, ignored -> machine.setTimeout(TIMEOUT_MS, later), null);
        CompletableFuture<Void> reachedLater = machine.stateReached(later);

        machine.receiveEvent(new AToB());

        reachedLater.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(later, machine.getState());
    }

    /**
     * A timeout armed in the target state's entry hook fires out of that state, running its exit
     * hooks rather than a second round of those for the state the transition left (#259).
     */
    @Test
    void aTimeoutArmedInAnEntryHookFiresOutOfTheStateItWasArmedFor() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State later = new State("LATER");
        StateMachine machine = machineFrom(a);
        List<String> exits = new CopyOnWriteArrayList<>();
        a.registerTransition(AToB.class, b);
        a.onExit(() -> exits.add("A"));
        b.onExit(() -> exits.add("B"));
        b.onEntry(() -> machine.setTimeout(TIMEOUT_MS, later));
        CompletableFuture<Void> reachedLater = machine.stateReached(later);

        machine.receiveEvent(new AToB());

        reachedLater.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of("A", "B"), exits, "the timeout must leave B, not leave A again");
    }

    /**
     * The same holds when the transition is a timeout's own: one armed by a firing timeout's action
     * survives that timeout's commit (#259).
     */
    @Test
    void aTimeoutArmedByATimeoutsActionSurvivesItsCommit() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State later = new State("LATER");
        StateMachine machine = machineFrom(a);
        CompletableFuture<Void> reachedLater = machine.stateReached(later);

        machine.setTimeout(EARLIER_TIMEOUT_MS, b, ignored -> machine.setTimeout(TIMEOUT_MS, later));

        reachedLater.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(later, machine.getState());
    }

    /**
     * The exemption covers only the transition in progress: a timeout armed on the way into a state
     * is disarmed by the next transition out of it, like any other (#259).
     */
    @Test
    void aTimeoutArmedDuringATransitionIsCancelledByTheNextOne() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");
        State later = new State("LATER");
        StateMachine machine = machineFrom(a);
        a.registerTransition(AToB.class, b, ignored -> machine.setTimeout(TIMEOUT_MS, later), null);
        b.registerTransition(BToC.class, c);

        // Both transitions under the machine's monitor; see the class javadoc.
        synchronized (machine) {
            machine.receiveEvent(new AToB());
            machine.receiveEvent(new BToC());
        }

        Thread.sleep(TIMEOUT_MS * 2); // past the deadline: a timeout still armed would have fired
        assertSame(c, machine.getState(), "leaving b must cancel the timeout armed on the way in");
    }
}
