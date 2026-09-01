package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A hook that throws must not derail the transition around it (WBS-2.3.7-fix, #258).
 *
 * <p>{@code Transition.transition} runs {@code from.exit()} then {@code to.entry()} and only then
 * returns the new state, so an escaping hook used to skip the state assignment, the timeout
 * cancellation and the {@code stateReached} completion — leaving the machine reporting a state it
 * had already run the exit hooks of. The three callers each disposed of that differently and none
 * repaired it; the worst was the CLI path, which hung forever on a future that could no longer
 * complete.
 */
@Timeout(15)
final class StateMachineHookFailureTest {

    private record Go() implements Event {}

    /** A hook that always throws, standing in for one whose real work failed. */
    private static Runnable throwing(final String message) {
        return () -> {
            throw new IllegalStateException(message);
        };
    }

    /**
     * The headline case: an entry hook throws, and the machine still arrives.
     *
     * <p>Previously this reported state A while having run A's exit hooks — the machine claiming to
     * be somewhere it had already left.
     */
    @Test
    void aThrowingEntryHookStillLeavesTheMachineInTheTargetState() {
        State a = new State("A");
        State b = new State("B");
        List<String> ran = new ArrayList<>();
        a.onExit(() -> ran.add("a.exit"));
        b.onEntry(throwing("entry blew up"));
        b.onEntry(() -> ran.add("b.entry2"));
        a.registerTransition(Go.class, b);
        StateMachine machine = new StateMachine(a);

        assertDoesNotThrow(() -> machine.receiveEvent(new Go()));

        assertEquals(b, machine.getState(), "the machine must arrive in B despite the throw");
        assertEquals(
                List.of("a.exit", "b.entry2"),
                ran,
                "the surviving hooks on both sides must still have run");
    }

    /** The same containment on the way out: an exit hook must not strand the machine either. */
    @Test
    void aThrowingExitHookStillLeavesTheMachineInTheTargetState() {
        State a = new State("A");
        State b = new State("B");
        List<String> ran = new ArrayList<>();
        a.onExit(throwing("exit blew up"));
        a.onExit(() -> ran.add("a.exit2"));
        b.onEntry(() -> ran.add("b.entry"));
        a.registerTransition(Go.class, b);
        StateMachine machine = new StateMachine(a);

        assertDoesNotThrow(() -> machine.receiveEvent(new Go()));

        assertEquals(b, machine.getState());
        assertEquals(List.of("a.exit2", "b.entry"), ran);
    }

    /**
     * The CLI hang, which is what made this more than a bookkeeping defect.
     *
     * <p>A subcommand blocks on {@code stateReached(TERMINATED).get()} with no timeout, and that
     * future is completed only after the state assignment a throwing hook used to skip. The main
     * thread then waited forever: the JVM never exited, the subprocess registry's shutdown hook
     * never ran, and any live child was orphaned. The wait below is bounded so a regression fails
     * this test instead of hanging the suite.
     */
    @Test
    void aThrowingEntryHookDoesNotStrandAWaiterOnTheTargetState() throws Exception {
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        terminated.onEntry(throwing("teardown blew up"));
        idle.registerTransition(Go.class, terminated);
        StateMachine machine = new StateMachine(idle);

        CompletableFuture<Void> reached = machine.stateReached(terminated);
        machine.receiveEvent(new Go());

        assertDoesNotThrow(
                () -> reached.get(5, TimeUnit.SECONDS),
                "stateReached must complete; a hung waiter here is a hung CLI");
    }

    /**
     * Pending timeouts are still cancelled. {@code commitTransition} does that after the assignment
     * the throw used to skip, so a stale timeout could previously fire against a state the machine
     * had already left.
     */
    @Test
    void aThrowingEntryHookStillCancelsPendingTimeouts() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State timedOut = new State("TIMED_OUT");
        b.onEntry(throwing("entry blew up"));
        a.registerTransition(Go.class, b);
        StateMachine machine = new StateMachine(a);
        machine.setTimeout(200, timedOut, ignored -> {});

        machine.receiveEvent(new Go());
        Thread.sleep(600);

        assertEquals(b, machine.getState(), "a cancelled timeout must not move the machine");
    }

    /** Every hook gets its turn: one thrower does not suppress the hooks registered after it. */
    @Test
    void oneThrowingHookDoesNotSuppressTheOthers() {
        State a = new State("A");
        State b = new State("B");
        List<String> ran = new ArrayList<>();
        b.onEntry(() -> ran.add("first"));
        b.onEntry(throwing("middle blew up"));
        b.onEntry(() -> ran.add("third"));
        a.registerTransition(Go.class, b);

        new StateMachine(a).receiveEvent(new Go());

        assertTrue(ran.contains("first") && ran.contains("third"), "ran: " + ran);
        assertEquals(2, ran.size(), "exactly the two non-throwing hooks should have run");
    }
}
