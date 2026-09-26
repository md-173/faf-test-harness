package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link StateMachine#cancel()} — the terminal stop of time-based scheduling used by the
 * shutdown path.
 */
final class StateMachineCancelTest {

    /** Upper bound on waiting for cancel() and for the transition it must not wait for. */
    private static final long CANCEL_WAIT_SECONDS = 5;

    /**
     * How long the in-flight action holds the monitor unless released. Longer than {@link
     * #CANCEL_WAIT_SECONDS}, so a cancel() that waited for the monitor would still be blocked when
     * the test gives up on it.
     */
    private static final long ACTION_HOLD_SECONDS = 10;

    @Test
    void cancelStopsPendingTimeoutFromFiring() throws Exception {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        // Armed and cancelled under the machine's monitor, which UpdateStateTask.run also takes, so
        // the timeout cannot fire first however late this thread runs (#380).
        synchronized (machine) {
            machine.setTimeout(150, c);
            machine.cancel();
        }

        Thread.sleep(300); // well past the 150ms timeout — it must not have fired
        assertSame(a, machine.getState(), "cancel() should stop the scheduled timeout");
    }

    @Test
    void cancelIsIdempotent() {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        machine.setTimeout(150, c);

        machine.cancel();
        assertDoesNotThrow(machine::cancel, "a second cancel() must be safe");
    }

    @Test
    void cancelWithNoPendingTimeoutsIsSafe() {
        StateMachine machine = new StateMachine(new State("A"));
        assertDoesNotThrow(machine::cancel, "cancel() on a machine that never scheduled is safe");
    }

    @Test
    void cancelLeavesAPendingStateReachedFutureIncomplete() {
        // Pins the documented gap on cancel(): it does not resolve stateReached() futures. See the
        // javadoc on cancel() (WBS-2.3.7-fix, #260) for why this is deliberate rather than an
        // oversight — an earlier attempt to resolve these futures here regressed the ordinary
        // shutdown path.
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        CompletableFuture<Void> awaiting = machine.stateReached(c);

        machine.cancel();

        assertFalse(
                awaiting.isDone(),
                "cancel() must not resolve a stateReached() future for a state it never reaches");
    }

    /**
     * cancel() never takes the machine's monitor (WBS-2.3.7-fix, #328), so it returns while a
     * transition action holds that monitor rather than waiting for the action to finish. The
     * transition in flight still commits afterwards: cancel() stops scheduling, not transitions.
     *
     * <p>The action waits longer for its release than the test waits for cancel(), so a cancel()
     * that took the monitor again would still be blocked when the test gives up on it. Both run on
     * daemon threads, because a thread blocked entering a monitor ignores interrupts: a regression
     * on the test thread would hang the build instead of failing this test.
     */
    @Test
    @Timeout(15)
    void cancelReturnsWhileATransitionActionIsInFlight() throws Exception {
        State a = new State("A");
        State b = new State("B");
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        a.registerTransition(
                Go.class,
                b,
                ignored -> {
                    actionEntered.countDown();
                    awaitQuietly(releaseAction, ACTION_HOLD_SECONDS);
                },
                null);
        StateMachine machine = new StateMachine(a);
        Thread transition = startDaemon(() -> machine.receiveEvent(new Go()), "transition");
        CountDownLatch cancelReturned = new CountDownLatch(1);
        try {
            assertTrue(
                    actionEntered.await(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS),
                    "the transition action should be running");
            startDaemon(
                    () -> {
                        machine.cancel();
                        cancelReturned.countDown();
                    },
                    "cancel");
            assertTrue(
                    cancelReturned.await(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS),
                    "cancel() must not wait for the transition action holding the monitor");
        } finally {
            releaseAction.countDown();
        }

        transition.join(TimeUnit.SECONDS.toMillis(CANCEL_WAIT_SECONDS));
        assertFalse(transition.isAlive(), "the released action should let its transition finish");
        assertSame(b, machine.getState(), "cancel() stops scheduling, not a transition in flight");
    }

    /**
     * cancel() leaves the thread running a timeout uninterrupted (WBS-2.3.7-fix, #465). A timeout's
     * own transition can call it and carry on: mock-game's GameShutdown does, from ENDED's entry
     * hook, whenever a timeout drives the game there, and an interrupt would cut its later steps
     * short. The executor's shutdownNow() would interrupt this thread; shutdown() cannot.
     */
    @Test
    void cancelFromATimeoutsOwnTransitionDoesNotInterruptIt() throws Exception {
        State a = new State("A");
        State ended = new State("ENDED");
        StateMachine machine = new StateMachine(a);
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        ended.onEntry(
                () -> {
                    machine.cancel();
                    interrupted.complete(Thread.currentThread().isInterrupted());
                });

        machine.setTimeout(0, ended);

        assertFalse(
                interrupted.get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS),
                "cancel() must not interrupt the thread running the timeout that called it");
    }

    /**
     * Pins #312's contract where it lives: cancel() is terminal, and a later setTimeout arms
     * nothing rather than throwing on the shut-down executor. A cancel() landing between that check
     * and the schedule is ruled out by construction, since both run under the machine's scheduling
     * lock.
     */
    @Test
    void setTimeoutAfterCancelDoesNotThrow() {
        State a = new State("A");
        State c = new State("C");
        StateMachine machine = new StateMachine(a);
        machine.cancel();

        assertDoesNotThrow(
                () -> machine.setTimeout(10, c), "a setTimeout after cancel() must arm nothing");
    }

    private final class Go implements Event {}

    /** Waits up to {@code seconds} for {@code latch}, keeping the interrupt if one arrives. */
    private static void awaitQuietly(final CountDownLatch latch, final long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Starts {@code body} on a named daemon thread. */
    private static Thread startDaemon(final Runnable body, final String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
