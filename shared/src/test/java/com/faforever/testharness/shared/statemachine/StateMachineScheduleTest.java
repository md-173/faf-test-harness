package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateMachine#schedule(long, Runnable)}: a plain task on the timeout thread,
 * which no commit disarms and {@link StateMachine#cancel()} stops (WBS-3.2.4.1-fix, #498).
 *
 * <p>Where a test needs a task still waiting at some moment, it first holds the machine's one
 * timeout thread with a blocking task, rather than counting on a delay to outlast this thread.
 */
final class StateMachineScheduleTest {

    /** Upper bound on each wait, not an expectation of how long it takes. */
    private static final long AWAIT_SECONDS = 5;

    /**
     * A commit does not disarm a scheduled task, as it would a timeout pending when it began. The
     * task waits behind the blocker until the commit has landed, so it is still pending then
     * however late this thread runs.
     */
    @Test
    void aStateChangeDoesNotDisarmAScheduledTask() throws Exception {
        State a = new State("A");
        State b = new State("B");
        a.registerTransition(Go.class, b);
        StateMachine machine = new StateMachine(a);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        try {
            holdTimeoutThread(machine, release);
            machine.schedule(0, ran::countDown);

            machine.receiveEvent(new Go());
            release.countDown();

            assertSame(b, machine.getState());
            assertTrue(
                    ran.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "a state change must not disarm a scheduled task");
        } finally {
            release.countDown();
            machine.cancel();
        }
    }

    /**
     * cancel() stops a scheduled task whether it is still waiting or already due, and later calls
     * schedule nothing. Both tasks are queued behind the blocker when cancel() runs. The timeout
     * thread then ends once its queue is empty, at once if the waiting task was discarded and a
     * minute later if not, so its ending proves the discard and also orders the due task, which the
     * executor still starts, before the check that it did nothing.
     */
    @Test
    void cancelStopsAScheduledTaskWaitingOrDue() throws Exception {
        StateMachine machine = new StateMachine(new State("A"));
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        Thread timeoutThread;
        try {
            timeoutThread = holdTimeoutThread(machine, release);
            machine.schedule(0, () -> ran.set(true));
            machine.schedule(TimeUnit.MINUTES.toMillis(1), () -> ran.set(true));

            machine.cancel();
        } finally {
            release.countDown();
        }

        timeoutThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertFalse(timeoutThread.isAlive(), "cancel() must discard a task still waiting");
        assertFalse(ran.get(), "a task already due when cancel() ran must do nothing");
        assertFalse(
                machine.schedule(0, () -> ran.set(true)),
                "schedule() after cancel() must schedule nothing");
    }

    /**
     * A scheduled task takes no monitor of the machine, so it runs while a transition driven by an
     * event holds that monitor on another thread, as an injected crash must to halt a game stuck in
     * one. This thread holds the monitor throughout, so a task that took it would still be waiting
     * when the wait below gives up.
     */
    @Test
    void aScheduledTaskRunsWhileTheMonitorIsHeld() throws Exception {
        StateMachine machine = new StateMachine(new State("A"));
        CountDownLatch ran = new CountDownLatch(1);
        try {
            synchronized (machine) {
                machine.schedule(0, ran::countDown);
                assertTrue(
                        ran.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                        "a scheduled task must not wait for the machine's monitor");
            }
        } finally {
            machine.cancel();
        }
    }

    /**
     * A scheduled task runs on the very thread the timeouts run on, which is what keeps the two in
     * one due order: the executor runs tasks due at the same instant first in, first out
     * (WBS-3.2.4.1-fix, #498). Run anywhere else, a second thread of the same name included, a task
     * would race the timeouts again, as mock-game's crash raced its match end (#487).
     */
    @Test
    void aScheduledTaskRunsOnTheTimeoutsThread() throws Exception {
        State a = new State("A");
        State b = new State("B");
        StateMachine machine = new StateMachine(a);
        CompletableFuture<Thread> timeoutRanOn = new CompletableFuture<>();
        CompletableFuture<Thread> taskRanOn = new CompletableFuture<>();
        try {
            machine.setTimeout(0, b, ignored -> timeoutRanOn.complete(Thread.currentThread()));
            machine.schedule(0, () -> taskRanOn.complete(Thread.currentThread()));

            assertSame(
                    timeoutRanOn.get(AWAIT_SECONDS, TimeUnit.SECONDS),
                    taskRanOn.get(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "a scheduled task must run on the thread the timeouts run on");
        } finally {
            machine.cancel();
        }
    }

    /** Event with no meaning beyond driving A to B. */
    private static final class Go implements Event {}

    /**
     * Occupies the machine's one timeout thread until {@code release} counts down, so whatever is
     * scheduled after this waits behind it, and returns that thread.
     */
    private static Thread holdTimeoutThread(
            final StateMachine machine, final CountDownLatch release) throws InterruptedException {
        AtomicReference<Thread> held = new AtomicReference<>();
        CountDownLatch holding = new CountDownLatch(1);
        machine.schedule(
                0,
                () -> {
                    held.set(Thread.currentThread());
                    holding.countDown();
                    awaitQuietly(release, AWAIT_SECONDS);
                });
        assertTrue(holding.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the blocker should be running");
        return held.get();
    }

    /** Waits up to {@code seconds} for {@code latch}, keeping the interrupt if one arrives. */
    private static void awaitQuietly(final CountDownLatch latch, final long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
