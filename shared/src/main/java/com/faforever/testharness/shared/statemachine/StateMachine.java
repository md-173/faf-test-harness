package com.faforever.testharness.shared.statemachine;

/** State machine, which drives transitions and keeps current state. */
public class StateMachine implements EventListener {
    /** The current state of the machine. */
    private State state;

    /**
     * Initializes the machine with its initial state.
     *
     * @param initialState the initial state of the machine.
     */
    public StateMachine(State initialState) {
        this.state = initialState;
    }

    /**
     * Getter for the current state of the machine.
     *
     * @return the current machine state.
     */
    public State getState() {
        return state;
    }

    /** Forwards event to its current state, then updates state. */
    @Override
    public void receiveEvent(Event event) {
        state = state.processEvent(event);
    }
}
