package com.faforever.testharness.shared.statemachine;

import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents a transition to another state. */
public class Transition {
    /** Logger instance for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(Transition.class);

    /** The state to transition from. */
    private final State from;

    /** The state to transition to. */
    private final State to;

    /** The action taken if a transition actually takes place. */
    private final TransitionAction action;

    /** Condition that must be met for a transition to happen. */
    private final Predicate<Event> guard;

    /**
     * Initializes a transition.
     *
     * @param from the state to transition from. Kept here in case a transition cannot occur.
     * @param to The state to transition to.
     * @param action The action taken if a transition actually takes place. Or {@code null} if no
     *     action.
     * @param guard Condition that must be met for a transition to happen. Or {@code null} if the
     *     transition always happens.
     */
    public Transition(State from, State to, TransitionAction action, Predicate<Event> guard) {
        this.from = from;
        this.to = to;
        this.action = action;
        this.guard = guard;
    }

    /**
     * Performs a transition, and all actions that occur due to it.
     *
     * <p>Returns {@code null} when no hooks fired and the machine stays in {@link Transition#from}:
     * either the action failed without naming a failure state, or this is a self-loop (an internal
     * transition, where the event is handled but the state does not change). Callers must treat
     * {@code null} as "this event was handled, but nothing about the state changed" and skip
     * everything they would otherwise do on a state change.
     *
     * <p>Note the one case where the state does not change and the return is still non-null: a
     * {@link FailedTransitionException} whose failure state is {@code from} itself. That is a
     * deliberate re-entry — {@code exit()} and {@code entry()} both fire — so it is reported as a
     * real transition and callers will treat it as one, cancelling pending timeouts included. Use a
     * null failure state, not {@code from}, to mean "stay put and change nothing".
     *
     * <p><b>An unchecked throw out of the action is contained here too</b> (WBS-2.3.7-fix, #326).
     * It used to escape into {@link StateMachine#receiveEvent(Event)}, which has no {@code
     * try}/{@code catch}, so the caller — a WebSocket handler or a {@code CompletableFuture}
     * continuation in production — received it, and in the second case it vanished into a dead
     * future with no trace. Containing it here rather than in {@code receiveEvent} keeps every
     * disposition of a failed action in one place and covers the timeout path with the same code.
     *
     * <p>A contained throw stays put: the machine is left in {@code from} and {@code null} is
     * returned, which is the same disposition as a {@link FailedTransitionException} carrying no
     * failure state. That is the only coherent reading — an action that crashes knows strictly less
     * than one that declared failure, so it cannot be allowed to drive a state change the declared
     * failure would not. Nothing is half-done either way: the action runs <em>before</em> {@code
     * exit()} and {@code entry()}, so a throw means no hook has fired yet.
     *
     * <p>The level is ERROR rather than the checked path's WARN. A declared failure is a modelled
     * outcome; an unchecked one is a bug in the action, and the stack trace is the only record of
     * it.
     *
     * @param event the event that triggers this transition.
     * @return the new state, or {@code null} if no hooks fired and the state did not change.
     */
    public State transition(Event event) {
        if (action != null) {
            try {
                action.accept(event);
            } catch (FailedTransitionException e) {
                if (e.getFailureState() != null) {
                    from.exit();
                    State s = e.getFailureState();
                    s.entry();
                    return s;
                } else {
                    LOG.warn(
                            "Transition action out of {} failed ({}), staying in {}",
                            from.getName(),
                            e.getMessage(),
                            from.getName(),
                            e);
                    return null;
                }
            } catch (RuntimeException e) {
                // Same disposition as a FailedTransitionException with no failure state, because an
                // unchecked throw cannot name one. Reported at ERROR: this is a defect in the
                // action, not a modelled failure, and it must not vanish.
                LOG.error(
                        "Transition action from {} to {} threw; staying in {}",
                        from.getName(),
                        to.getName(),
                        from.getName(),
                        e);
                return null;
            }
        }
        // A self-loop (from == to) is a stay-in-state action: the event is handled but no actual
        // state change occurs, so exit/entry hooks must not re-fire (they would otherwise re-run
        // side effects such as teardown that are only meant to happen once, on genuine entry).
        if (from == to) {
            return null;
        }
        from.exit();
        to.entry();
        return to;
    }

    /**
     * Run the {@code Transition}'s guard against the given event.
     *
     * @param event the event that is triggering the potential transition.
     * @return the value the guard evaluates to. If no guard exists, it is {@code true} by default.
     */
    public boolean guard(Event event) {
        return guard == null || guard.test(event);
    }
}
