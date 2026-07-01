package com.faforever.testharness.shared.statemachine;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Represents a transition to another state. */
public class Transition {
    /** The state to transition from. */
    private State from;

    /** The state to transition to. */
    private State to;

    /** The action taken if a transition actually takes place. */
    private Consumer<Event> action;

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
    public Transition(State from, State to, Consumer<Event> action, Predicate<Event> guard) {
        this.from = from;
        this.to = to;
        this.action = action;
        this.guard = guard;
    }

    /**
     * Performs a transition, and all actions that occur due to it.
     *
     * @param event the event that triggers this transition.
     * @return the new state.
     */
    public State transition(Event event) {
        if (action != null) {
            action.accept(event);
        }
        // Run any registered hooks.
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
