package com.faforever.testharness.shared.statemachine;

@FunctionalInterface
public interface TransitionAction {
    /**
     * Execute the action for the given event.
     *
     * @param event the event that triggered this transition.
     */
    void accept(Event event) throws FailedTransitionException;
}
