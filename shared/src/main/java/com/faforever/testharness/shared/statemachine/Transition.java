package com.faforever.testharness.shared.statemachine;

import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents a transition to another state. */
public class Transition {
    /** Logger instance for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(Transition.class);

    /** The state to transition from. */
    private State from;

    /** The state to transition to. */
    private State to;

    /** The action taken if a transition actually takes place. */
    private TransitionAction action;

    /** Condition that must be met for a transition to happen. */
    private Predicate<Event> guard;

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
     * <p>Returns {@code null} exactly when no transition occurred: no exit/entry hooks fired and
     * the machine must stay in {@link Transition#from}. That happens either because the action
     * failed without naming a failure state, or because this is a self-loop (an internal
     * transition, where the event is handled but the state does not change). Callers must treat
     * {@code null} as "this event was handled, but nothing about the state changed" and skip
     * everything they would otherwise do on a state change.
     *
     * @param event the event that triggers this transition.
     * @return the new state, or {@code null} if no transition occurred.
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
                            from.getName());
                    return null;
                }
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
