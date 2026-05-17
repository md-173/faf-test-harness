package com.faforever.testharness.shared.statemachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

/** Represents a state. */
public class State {
    /** A unique name for this state. */
    private final String name;

    /** Each type of event drives one transition. */
    private final HashMap<Class<? extends Event>, Transition> transitions;

    /** A set of hooks to run when this state is entered. */
    private final List<Runnable> entryHooks;

    /** A set of hooks to run when this state is exited. */
    private final List<Runnable> exitHooks;

    /**
     * Initializes the state.
     *
     * @param name the given name of the state.
     */
    public State(String name) {
        this.name = name;
        this.transitions = new HashMap<>();
        this.entryHooks = new ArrayList<>();
        this.exitHooks = new ArrayList<>();
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
     * Register an action to run when this state is entered.
     *
     * @param action the action to run.
     */
    public void onEntry(Runnable action) {
        entryHooks.add(action);
    }

    /**
     * Register an action to run when this state is exited.
     *
     * @param action the action to run.
     */
    public void onExit(Runnable action) {
        exitHooks.add(action);
    }

    /** Perform all entry actions. */
    public void entry() {
        for (var hook : entryHooks) {
            hook.run();
        }
    }

    /** Perform all exit actions. */
    public void exit() {
        for (var hook : exitHooks) {
            hook.run();
        }
    }

    /**
     * Create a simple transition from this state to {@link other}, any time {@link event} happens.
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     */
    public void registerTransition(Class<? extends Event> event, State other) {
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
            Class<? extends Event> event, State other, Runnable action, Callable<Boolean> guard) {
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
        Transition t = transitions.get(event.getClass());
        if (t != null) {
            return t.transition();
        } else {
            return this;
        }
    }
}
