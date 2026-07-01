package com.faforever.testharness.shared.statemachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Represents a state. */
public class State {
    /** A unique name for this state. */
    private final String name;

    /** Each type of event drives a set of potential transition. */
    private final HashMap<Class<? extends Event>, List<Transition>> transitions;

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
     * Create a simple transition from this state to {@code other}, any time {@code event} happens.
     * Transitions are tried in the order they were registered and the first one to succeed will be
     * the only one to occur.
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     */
    public void registerTransition(Class<? extends Event> event, State other) {
        registerTransition(event, other, null, null);
    }

    /**
     * Create a transition from this state to {@code other} any time {@code event} and also {@code
     * guard} evaluates to {@code true}. This transition calls {@code action}. Transitions are tried
     * in the order they were registered and the first one to succeed will be the only one to occur.
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     * @param action occurs upon succesful transition.
     * @param guard must be true for transition to happen.
     */
    public void registerTransition(
            Class<? extends Event> event,
            State other,
            Consumer<Event> action,
            Predicate<Event> guard) {
        Transition t = new Transition(this, other, action, guard);
        transitions.computeIfAbsent(event, k -> new ArrayList<>()).add(t);
    }

    /**
     * Obtains the corresponding transition for the event type.
     *
     * @param event the event type
     * @return a list of all matching transitions. Will be empty if no transitions were registered
     *     for this event type.
     */
    public List<Transition> getTransitions(Class<? extends Event> event) {
        return transitions.getOrDefault(event, List.of());
    }
}
