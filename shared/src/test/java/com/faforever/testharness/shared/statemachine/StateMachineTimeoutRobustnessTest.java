package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests the two ways a scheduled timeout can misbehave once it has already been handed to the timer
 * thread: firing after it was cancelled, and throwing something other than {@link
 * FailedTransitionException}.
 */
final class StateMachineTimeoutRobustnessTest {
    private static final long TIMEOUT_MS = 100;
    private static final long SETTLE_MS = 300;
    private static final int AWAIT_SECONDS = 2;

    private final class Go implements Event {}

    private ListAppender<ILoggingEvent> appender;
    private Logger machineLogger;
    private Level originalLevel;

    /**
     * Captures {@link StateMachine}'s own DEBUG output. The cancellation test needs it to prove it
     * actually entered the race window rather than passing because the task was never dequeued.
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
        machine.setTimeout(TIMEOUT_MS, doom);

        // Take the monitor before the timeout is due, so the timer thread dequeues the task and
        // then parks inside run() waiting for us — the window cancel() cannot close.
        synchronized (machine) {
            Thread.sleep(SETTLE_MS);
            machine.receiveEvent(new Go());
            assertSame(b, machine.getState());
        }

        // The released task must notice it is no longer pending and do nothing.
        Thread.sleep(SETTLE_MS);
        assertSame(b, machine.getState(), "a cancelled timeout must not commit a transition");
        // Without this the test passes vacuously whenever the timer thread was starved past the
        // window: the task is then never dequeued, cancel() drops it cleanly, and nothing under
        // test ever runs.
        assertTrue(
                logged("Timeout fired after being cancelled"),
                () ->
                        "the race window was never entered, so this proved nothing; log was "
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
