package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests the "stay put" outcomes of a transition: a {@link FailedTransitionException} carrying no
 * failure state, and a self-loop. In both cases the event is handled but the machine does not
 * change state, so none of the bookkeeping that follows a real transition may run — in particular
 * pending timeouts must stay armed.
 */
final class StateMachineStayPutTest {
    private static final int AWAIT_SECONDS = 2;
    private static final long TIMEOUT_MS = 150;

    private final class Trigger implements Event {}

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            appender.stop();
            root.detachAppender(appender);
        }
    }

    private boolean loggedWarnContaining(String... fragments) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(
                        message -> {
                            for (var fragment : fragments) {
                                if (!message.contains(fragment)) {
                                    return false;
                                }
                            }
                            return true;
                        });
    }

    @Test
    void stayPutFailureWarnsAndLeavesStateUntouched() {
        State a = new State("A");
        State b = new State("B");
        AtomicInteger hookRuns = new AtomicInteger();
        a.onExit(hookRuns::incrementAndGet);
        a.onEntry(hookRuns::incrementAndGet);
        b.onEntry(hookRuns::incrementAndGet);

        a.registerTransition(
                Trigger.class,
                b,
                ignored -> {
                    throw new FailedTransitionException("action blew up");
                },
                null);
        StateMachine machine = new StateMachine(a);

        machine.receiveEvent(new Trigger());

        assertSame(a, machine.getState());
        assertEquals(0, hookRuns.get(), "no exit or entry hook may fire on a stay-put failure");
        assertTrue(
                loggedWarnContaining("action blew up", "A"),
                "a stay-put failure must not be silent: expected a WARN naming the failure and the"
                        + " state stayed in, got "
                        + appender.list);
    }

    @Test
    void stayPutFailureWarnCarriesTheException() {
        State a = new State("A");
        State b = new State("B");

        a.registerTransition(
                Trigger.class,
                b,
                ignored -> {
                    throw new FailedTransitionException("action blew up");
                },
                null);
        StateMachine machine = new StateMachine(a);

        machine.receiveEvent(new Trigger());

        assertTrue(
                appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .anyMatch(e -> e.getThrowableProxy() != null),
                "the WARN must carry the exception so the failure has a stack trace");
    }

    /**
     * A failure state equal to the state being left is a deliberate re-entry, not a stay-put: hooks
     * fire and it is reported as a real transition. Pinned because {@link Transition#transition}
     * now documents it as the one case where the state is unchanged but the return is non-null.
     */
    @Test
    void failureStateEqualToCurrentStateIsARealTransition() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");
        AtomicInteger hookRuns = new AtomicInteger();
        a.onExit(hookRuns::incrementAndGet);
        a.onEntry(hookRuns::incrementAndGet);

        a.registerTransition(
                Trigger.class,
                b,
                ignored -> {
                    throw new FailedTransitionException("re-enter", a);
                },
                null);
        StateMachine machine = new StateMachine(a);
        machine.setTimeout(TIMEOUT_MS, c);

        machine.receiveEvent(new Trigger());

        assertSame(a, machine.getState());
        assertEquals(2, hookRuns.get(), "a re-entry fires exit and entry once each");

        // Treated as a real transition, so the pending timeout is cancelled.
        Thread.sleep(TIMEOUT_MS * 3);
        assertSame(a, machine.getState(), "the re-entry cancelled the pending timeout");
    }

    @Test
    void stayPutFailureDoesNotCancelPendingTimeout() throws Exception {
        State a = new State("A");
        State b = new State("B");
        State c = new State("C");

        a.registerTransition(
                Trigger.class,
                b,
                ignored -> {
                    throw new FailedTransitionException("action blew up");
                },
                null);
        StateMachine machine = new StateMachine(a);

        machine.setTimeout(TIMEOUT_MS, c);
        var reachedC = machine.stateReached(c);

        // A failed action must not disarm a timeout it has nothing to do with.
        machine.receiveEvent(new Trigger());
        assertSame(a, machine.getState());

        reachedC.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(c, machine.getState());
    }

    @Test
    void selfLoopRunsActionButDoesNotCancelPendingTimeout() throws Exception {
        State a = new State("A");
        State c = new State("C");
        AtomicInteger hookRuns = new AtomicInteger();
        AtomicInteger actionRuns = new AtomicInteger();
        a.onExit(hookRuns::incrementAndGet);
        a.onEntry(hookRuns::incrementAndGet);

        a.registerTransition(Trigger.class, a, ignored -> actionRuns.incrementAndGet(), null);
        StateMachine machine = new StateMachine(a);

        machine.setTimeout(TIMEOUT_MS, c);
        var reachedC = machine.stateReached(c);

        machine.receiveEvent(new Trigger());
        assertEquals(1, actionRuns.get(), "the self-loop action still runs");
        assertEquals(0, hookRuns.get(), "a self-loop must not re-fire exit or entry hooks");
        assertSame(a, machine.getState());

        reachedC.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(c, machine.getState());
    }

    @Test
    void timeoutIntoCurrentStateDoesNotCancelOtherTimeouts() throws Exception {
        State a = new State("A");
        State c = new State("C");

        StateMachine machine = new StateMachine(a);
        var reachedC = machine.stateReached(c);

        // A timeout into the state we are already in changes nothing, so the later one survives.
        machine.setTimeout(TIMEOUT_MS, a);
        machine.setTimeout(TIMEOUT_MS * 2, c);

        reachedC.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(c, machine.getState());
    }
}
