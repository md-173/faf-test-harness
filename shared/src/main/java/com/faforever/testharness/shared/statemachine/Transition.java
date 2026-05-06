package com.faforever.testharness.shared.statemachine;

import java.util.concurrent.Callable;

/** Represents a transition to another state. */
public class Transition {
    /** The state to transition from. */
    private State from;

    /** The state to transition to. */
    private State to;

    /** The action taken if a transition actually takes place. */
    private Runnable action;

    /** Condition that must be met for a transition to happen. */
    private Callable<Boolean> guard;

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
    public Transition(State from, State to, Runnable action, Callable<Boolean> guard) {
        this.from = from;
        this.to = to;
        this.action = action;
        this.guard = guard;
    }

    /**
     * Attempts a transition.
     *
     * @return the new state, which might be the old state (i.e. if the guard fails).
     */
    public State transition() {
        try {
            if (guard == null || guard.call()) {
                if (action != null) {
                    action.run();
                }
                return to;
            }
            // No transition, returns old state.
            return from;
        } catch (Exception e) {
            // Exception is thrown by guard.call() if the result couldn't be computed.
            // In this case, simply return previous state.
            return from;
        }
    }
}
