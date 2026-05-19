package com.faforever.testharness.shared.statemachine;

import java.util.function.Predicate;

/** Represents a transition to another state. */
public class Transition {
    /** The state to transition from. */
    private State from;

    /** The state to transition to. */
    private State to;

    /** The action taken if a transition actually takes place. */
    private Runnable action;

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
    public Transition(State from, State to, Runnable action, Predicate<Event> guard) {
        this.from = from;
        this.to = to;
        this.action = action;
        this.guard = guard;
    }

    /**
     * Attempts a transition.
     *
     * @param event the event that triggered this transition.
     * @return the new state, which might be the old state (i.e. if the guard fails).
     */
    public State transition(Event event) {
        if (guard == null || guard.test(event)) {
            if (action != null) {
                action.run();
            }
            // Run any registered hooks.
            from.exit();
            to.entry();
            return to;
        }
        // No transition, returns old state.
        return from;
    }
}
