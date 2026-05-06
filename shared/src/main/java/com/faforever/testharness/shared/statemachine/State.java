package com.faforever.testharness.shared.statemachine;

import java.util.HashMap;
import java.util.concurrent.Callable;

/** Represents a state. */
public class State {
    /** A unique name for this state. */
    private final String name;

    /** Each event drives one transition. */
    private HashMap<Event, Transition> transitions;

    /**
     * Initializes the state.
     *
     * @param name the given name of the state.
     */
    public State(String name) {
        this.name = name;
        this.transitions = transitions;
    }

    /**
     * Getter for the state's name.
     *
     * @return the given name.
     */
    public String getName() {
        return name;
    }

    /**
     * Create a simple transition from this state to {@link other}, any time {@link event} happens.
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     */
    public void registerTransition(Event event, State other) {
        registerTransition(event, other, null, null);
    }

    /**
     * Create a transition from this state to {@link other} any time {@link event} and also {@link
     * guard} evaluates to {@code true}. This transition calls {@link action}
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     * @param action occurs upon succesful transition.
     * @param guard must be true for transition to happen.
     */
    public void registerTransition(
            Event event, State other, Runnable action, Callable<Boolean> guard) {
        Transition t = new Transition(this, other, action, guard);
        transitions.put(event, t);
    }

    /**
     * Processes an event and potentially changes state.
     *
     * @param event the trigger event.
     * @return the new state, or potentially itself if no transition occurred.
     */
    public State processEvent(Event event) {
        Transition t = transitions.get(event);
        if (t != null) {
            return t.transition();
        } else {
            return this;
        }
    }
}
