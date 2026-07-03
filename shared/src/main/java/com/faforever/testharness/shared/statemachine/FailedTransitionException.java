package com.faforever.testharness.shared.statemachine;

/**
 * A checked exception used to represent that a transition failed to happen during the action. There
 * is an optional {@link FailedTransitionException#failureState} if the state machine should
 * immediately go to a particular state.
 */
public class FailedTransitionException extends Exception {

    /**
     * The failure state for the state machine to go to, or null if the machine should stay in its
     * current state.
     */
    private final State failureState;

    /**
     * Construct an exception with the given message and in which the state machine does not
     * transition.
     *
     * @param message the textual representation of the exception.
     */
    public FailedTransitionException(String message) {
        this(message, null);
    }

    /**
     * Construct an exception with the given message and force the state machine to go to {@code
     * failureState}.
     *
     * @param message the textual representation of the exception.
     * @param failureState the state to transition to now.
     */
    public FailedTransitionException(String message, State failureState) {
        super(message);
        this.failureState = failureState;
    }

    /**
     * Get the failure state set by the caller.
     *
     * @return the failure state, or {@code null} if there is no failure state.
     */
    public State getFailureState() {
        return failureState;
    }
}
