package com.faforever.testharness.shared.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/** State machine, which drives transitions and keeps current state. */
public class StateMachine implements EventListener {
    /** The current state of the machine. */
    private State state;

    /** The policy to use when an event is received and no matching transition is found. */
    private final InvalidTransitionPolicy transitionPolicy;

    /** Timer used for timeouts. */
    private final Timer timeoutTimer;

    /** Collection of tasks scheduled, kept to cancel them later if necessary. */
    private final List<TimerTask> timeouts;

    /**
     * Initializes the machine with its initial state and policy.
     *
     * @param initialState the initial state of the machine.
     * @param policy the policy to use when an event is received and no transition is found.
     */
    public StateMachine(State initialState, InvalidTransitionPolicy policy) {
        this.state = initialState;
        this.transitionPolicy = policy;
        this.timeoutTimer = new Timer();
        this.timeouts = new ArrayList<>();
    }

    /**
     * Initializes the machine with its initial state.
     *
     * @param initialState the initial state of the machine.
     */
    public StateMachine(State initialState) {
        this(initialState, InvalidTransitionPolicy.IGNORE);
    }

    /**
     * Getter for the current state of the machine.
     *
     * @return the current machine state.
     */
    public State getState() {
        return state;
    }

    /**
     * Forwards event to its current state, then updates state.
     *
     * @throws InvalidTransitionException if the {@link StateMachine} policy was set to {@link
     *     InvalidTransitionPolicy.THROW} and the transition is invalid/non-existent.
     */
    @Override
    public synchronized void receiveEvent(Event event) {
        Transition t = state.getTransition(event.getClass());
        if (t != null) {
            State newState = t.transition();
            if (newState != state) {
                state = newState;
                for (var timeout : timeouts) {
                    timeout.cancel();
                }
                timeouts.clear();
            }
        } else if (transitionPolicy == InvalidTransitionPolicy.THROW) {
            throw new InvalidTransitionException(
                    String.format("No valid transitions for {}", event.toString()));
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
        /** Transition to fire when the timeout finishes. */
        private final Transition transition;

        UpdateStateTask(State to) {
            // Wrap state in a transition so that entry and exit hooks are performed correctly.
            // Safe to give current `state` as `from` parameter as the task will be cancelled if
            // state changes.
            this.transition = new Transition(state, to, null, null);
        }

        @Override
        public void run() {
            // Synchronize with receiveEvent by using the outer class instance as monitor.
            synchronized (StateMachine.this) {
                state = transition.transition();
                // State transition occured, any other timeouts are cancelled.
                for (var timeout : timeouts) {
                    timeout.cancel();
                }
                timeouts.clear();
            }
        }
    }
}
