package com.faforever.testharness.shared.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/** State machine, which drives transitions and keeps current state. */
public class StateMachine implements EventListener {
    /** The current state of the machine. */
    private State state;

    /** Timer used for timeouts. */
    private final Timer timeoutTimer;

    /** Collection of tasks scheduled, kept to cancel them later if necessary. */
    private final List<TimerTask> timeouts;

    /**
     * Initializes the machine with its initial state.
     *
     * @param initialState the initial state of the machine.
     */
    public StateMachine(State initialState) {
        this.state = initialState;
        this.timeoutTimer = new Timer();
        this.timeouts = new ArrayList<>();
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
    public synchronized void receiveEvent(Event event) {
        State newState = state.processEvent(event);
        if (newState != state) {
            state = newState;
            // State transition, so cancel any timeouts.
            for (var timeout : timeouts) {
                timeout.cancel();
            }
            timeouts.clear();
        }
    }

    /**
     * Sets up a timeout that will cause a transition to state {@link to} if no other transition
     * after {@link millis} elapses.
     *
     * @param millis the time in milliseconds to wait before changing states.
     * @param to the new state to go to.
     */
    public void setTimeout(long millis, State to) {
        UpdateStateTask task = new UpdateStateTask(to);
        timeouts.add(task);
        timeoutTimer.schedule(task, millis);
    }

    private class UpdateStateTask extends TimerTask {
        /** State to transition to. */
        private State to;

        UpdateStateTask(State to) {
            this.to = to;
        }

        @Override
        public synchronized void run() {
            state = to;
            // State transition occured, any other timeouts are cancelled.
            for (var timeout : timeouts) {
                timeout.cancel();
            }
            timeouts.clear();
        }
    }
}
