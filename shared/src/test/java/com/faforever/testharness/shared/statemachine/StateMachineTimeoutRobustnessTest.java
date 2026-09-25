package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests the two ways a scheduled timeout can misbehave once it has already been handed to the timer
 * thread: firing after it was cancelled, whether by a commit or by {@link StateMachine#cancel()},
 * and throwing something other than {@link FailedTransitionException}.
 */
final class StateMachineTimeoutRobustnessTest {
    private static final long TIMEOUT_MS = 100;
    private static final long SETTLE_MS = 300;

    /** Upper bound on every wait here, not an expectation of how long it takes. */
    private static final int AWAIT_SECONDS = 5;

    private static final long POLL_MS = 10;

    private final class Go implements Event {}

    private ListAppender<ILoggingEvent> appender;
    private Logger machineLogger;
    private Level originalLevel;

    /**
     * Captures {@link StateMachine}'s own DEBUG output. The cancellation test awaits the released
     * task's own line, which proves the task ran and took the cancelled branch.
     */
    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        machineLogger = ctx.getLogger(StateMachine.class);
        originalLevel = machineLogger.getLevel();
        machineLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        // The timer thread appends while the test thread asserts, so this one is load-bearing.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        machineLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            appender.stop();
            machineLogger.detachAppender(appender);
            machineLogger.setLevel(originalLevel);
        }
    }

    private boolean logged(String fragment) {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
    }

    /** Waits, within {@link #AWAIT_SECONDS}, for {@link StateMachine} to log {@code fragment}. */
    private void awaitLogged(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!logged(fragment)) {
            assertTrue(
                    System.nanoTime() < deadline,
                    () -> "never logged \"" + fragment + "\"; log was " + appender.list);
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * The machine's timer thread, captured by a timeout due at once into the state the machine is
     * already in. Such a timeout runs its action and changes nothing else.
     */
    private static Thread timerThreadOf(StateMachine machine, State current) throws Exception {
        CompletableFuture<Thread> timer = new CompletableFuture<>();
        machine.setTimeout(0, current, ignored -> timer.complete(Thread.currentThread()));
        return timer.get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Waits, within {@link #AWAIT_SECONDS}, until {@code timer} has dequeued a timeout and is
     * parked on the machine's monitor inside its {@code run()}. The frame check matters: {@link
     * Thread.State#BLOCKED} also covers re-entering a monitor after {@link Object#wait()}, which is
     * how the timer thread waits for its next task.
     */
    private static void awaitParkedOnMonitor(Thread timer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (timer.getState() != Thread.State.BLOCKED || !runningTimeout(timer)) {
            assertTrue(
                    System.nanoTime() < deadline,
                    "the timer thread never parked on the machine's monitor");
            Thread.sleep(POLL_MS);
        }
    }

    private static boolean runningTimeout(Thread timer) {
        for (StackTraceElement frame : timer.getStackTrace()) {
            if (frame.getClassName().endsWith("$UpdateStateTask")) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link java.util.TimerTask#cancel()} cannot stop a task the timer thread has already
     * dequeued. Such a task blocks on the machine's monitor and, once released, used to commit a
     * transition out of a state the machine had already left.
     */
    @Test
    void timeoutCancelledAfterBeingDequeuedDoesNotCommit() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State doom = new State("DOOM");

        a.registerTransition(Go.class, b);
        StateMachine machine = new StateMachine(a);
        Thread timer = timerThreadOf(machine, a);

        // The monitor is held from before the timeout is armed, so it cannot commit ahead of the
        // event however late this thread runs (#380). Due at once, the timeout is dequeued by the
        // timer thread, which then parks inside run() waiting for us: the window TimerTask.cancel()
        // cannot close. Waiting for that park, rather than sleeping a fixed settle, is what proves
        // the window was entered at all.
        synchronized (machine) {
            machine.setTimeout(0, doom);
            awaitParkedOnMonitor(timer);
            machine.receiveEvent(new Go());
            assertSame(b, machine.getState());
        }

        // The released task must notice it is no longer pending and do nothing.
        awaitLogged("Timeout fired after being cancelled");
        assertSame(b, machine.getState(), "a cancelled timeout must not commit a transition");
    }

    /**
     * {@link StateMachine#cancel()} never takes the machine's monitor (WBS-2.3.7-fix, #328), so it
     * cannot wait out a timeout the timer thread has already dequeued and parked on that monitor.
     * Nothing else stops that task either: cancel() leaves the pending list alone, so the task
     * still finds itself armed once released, and only the cancelled flag keeps it from committing.
     */
    @Test
    void timeoutDequeuedBeforeCancelDoesNotCommitAfterIt() throws Exception {
        State a = new State("A");
        State doom = new State("DOOM");
        StateMachine machine = new StateMachine(a);
        Thread timer = timerThreadOf(machine, a);

        // Parked exactly as in timeoutCancelledAfterBeingDequeuedDoesNotCommit, but released by
        // cancel() rather than by a commit.
        synchronized (machine) {
            machine.setTimeout(0, doom);
            awaitParkedOnMonitor(timer);
            machine.cancel();
        }

        // cancel() stopped the timer, so its thread ends once the released task has returned.
        timer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertFalse(timer.isAlive(), "the timer thread should end after its last task");
        assertSame(a, machine.getState(), "a timeout dequeued before cancel() must not commit");
        assertTrue(
                logged("Timeout fired after scheduling was cancelled"),
                () ->
                        "the released task must stop on the cancelled flag; log was "
                                + appender.list);
    }

    /**
     * A runtime exception thrown by a timeout action used to escape {@code run()} and kill the
     * timer thread, after which every later {@link StateMachine#setTimeout(long, State)} threw.
     */
    @Test
    void runtimeExceptionInTimeoutActionLeavesTimerUsable() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        StateMachine machine = new StateMachine(a);
        machine.setTimeout(
                TIMEOUT_MS,
                b,
                ignored -> {
                    throw new IllegalStateException("action blew up");
                });

        Thread.sleep(SETTLE_MS);
        assertSame(a, machine.getState(), "a throwing action must not move the machine");

        var reachedC = machine.stateReached(c);
        assertDoesNotThrow(
                () -> machine.setTimeout(TIMEOUT_MS, c),
                "the timer must survive a throwing action");
        reachedC.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(c, machine.getState());
    }
}
