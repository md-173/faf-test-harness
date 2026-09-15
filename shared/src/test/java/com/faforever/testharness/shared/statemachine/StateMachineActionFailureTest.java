package com.faforever.testharness.shared.statemachine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * A transition action that throws something unchecked (WBS-2.3.7-fix, #326).
 *
 * <p>{@link Transition#transition(Event)} caught only the checked {@link
 * FailedTransitionException}, so anything else escaped into {@link
 * StateMachine#receiveEvent(Event)} — which has no {@code try}/{@code catch} — and out to whichever
 * thread posted the event. In production that is a WebSocket handler or a {@code CompletableFuture}
 * continuation, and in the second case the throw vanished into a dead future with no trace at all.
 *
 * <p>Sibling of {@code StateMachineStayPutTest}: a contained throw has the same disposition as a
 * {@link FailedTransitionException} with no failure state, so the machine stays put and none of the
 * bookkeeping that follows a real transition may run. What differs is the level — ERROR rather than
 * WARN, because a crash is a defect in the action rather than a modelled outcome.
 *
 * <p>Every wait here is bounded, so a regression fails rather than hanging the suite.
 */
@Timeout(15)
final class StateMachineActionFailureTest {

    /** Budget for a future that should already be complete. */
    private static final int AWAIT_SECONDS = 2;

    /** Long enough that it cannot fire during a test, short enough not to stall the suite. */
    private static final long TIMEOUT_MS = 5000;

    private final class Trigger implements Event {}

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // Root-attached, so any thread in this forked JVM can append while assertions iterate.
        appender.list = new CopyOnWriteArrayList<>();
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

    /** An action that throws the given unchecked exception when it runs. */
    private static TransitionAction throwing(final String message) {
        return event -> {
            throw new IllegalStateException(message);
        };
    }

    private Optional<ILoggingEvent> errorContaining(final String... fragments) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(
                        e -> {
                            String message = e.getFormattedMessage();
                            for (var fragment : fragments) {
                                if (!message.contains(fragment)) {
                                    return false;
                                }
                            }
                            return true;
                        })
                .findFirst();
    }

    /**
     * The escape itself. In production the caller is a reader thread or a {@code CompletableFuture}
     * continuation, so this throw either killed a pump or was swallowed into a future nobody reads.
     */
    @Test
    void anUncheckedThrowDoesNotEscapeReceiveEvent() {
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        idle.registerTransition(Trigger.class, terminated, throwing("action boom"), null);
        StateMachine machine = new StateMachine(idle);

        assertDoesNotThrow(
                () -> machine.receiveEvent(new Trigger()),
                "a throwing action must not propagate to whoever posted the event");
    }

    /** Contained, not silenced: an action that crashed did not do its job and must say so. */
    @Test
    void theThrowIsReportedAtErrorNamingTheTransition() {
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        idle.registerTransition(Trigger.class, terminated, throwing("action boom"), null);
        StateMachine machine = new StateMachine(idle);

        machine.receiveEvent(new Trigger());

        Optional<ILoggingEvent> reported = errorContaining("IDLE", "TERMINATED", "threw");
        assertTrue(
                reported.isPresent(),
                "the failure must be reported at ERROR, naming the transition: " + appender.list);
        assertEquals(
                IllegalStateException.class.getName(),
                reported.get().getThrowableProxy().getClassName(),
                "the stack trace is the only record of what actually broke, so it must be carried");
    }

    /**
     * Stay put, the same disposition as a {@link FailedTransitionException} with no failure state.
     * An action that crashes knows strictly less than one that declared failure, so it must not
     * drive a state change the declared failure would not.
     */
    @Test
    void theMachineStaysInTheStateItStartedIn() {
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        idle.registerTransition(Trigger.class, terminated, throwing("action boom"), null);
        StateMachine machine = new StateMachine(idle);

        machine.receiveEvent(new Trigger());

        assertSame(idle, machine.getState(), "no hook fired, so nothing about the state changed");
    }

    /**
     * No hook runs on either side. The action is attempted before {@code exit()} and {@code
     * entry()}, so a throw leaves nothing half-done — this pins that ordering, since containing the
     * throw after the hooks had run would leave exactly the partial transition #258 fixed for the
     * hook path.
     */
    @Test
    void neitherExitNorEntryHooksFire() {
        AtomicInteger exits = new AtomicInteger();
        AtomicInteger entries = new AtomicInteger();
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        idle.onExit(exits::incrementAndGet);
        terminated.onEntry(entries::incrementAndGet);
        idle.registerTransition(Trigger.class, terminated, throwing("action boom"), null);
        StateMachine machine = new StateMachine(idle);

        machine.receiveEvent(new Trigger());

        assertEquals(0, exits.get(), "the machine never left IDLE, so its exit hook must not run");
        assertEquals(0, entries.get(), "TERMINATED was never entered");
    }

    /**
     * Pending timeouts stay armed, because a timeout is waiting for a state change and there was
     * none. This is the half that makes containment safe rather than merely quiet: the timeout is
     * what still ends a session whose action keeps failing.
     */
    @Test
    void pendingTimeoutsStayArmed() throws Exception {
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        State timedOut = new State("TIMED_OUT");
        idle.registerTransition(Trigger.class, terminated, throwing("action boom"), null);
        StateMachine machine = new StateMachine(idle);
        machine.setTimeout(TIMEOUT_MS, timedOut, ignored -> {});

        machine.receiveEvent(new Trigger());

        assertThrows(
                TimeoutException.class,
                () -> machine.stateReached(timedOut).get(1, TimeUnit.SECONDS),
                "the timeout must still be pending, not cancelled by a transition that never was");
        assertSame(idle, machine.getState());
    }

    /**
     * The machine is still usable afterwards. Containment would be worth little if the first
     * crashing action left the FSM unable to move — this is what lets a retry, or the session's own
     * teardown event, still get through.
     */
    @Test
    void aLaterEventStillDrivesTheMachine() throws Exception {
        State idle = new State("IDLE");
        State terminated = new State("TERMINATED");
        idle.registerTransition(Trigger.class, terminated, throwing("action boom"), null);
        idle.registerTransition(Recover.class, terminated);
        StateMachine machine = new StateMachine(idle);

        machine.receiveEvent(new Trigger());
        machine.receiveEvent(new Recover());

        machine.stateReached(terminated).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertSame(terminated, machine.getState());
    }

    private final class Recover implements Event {}
}
