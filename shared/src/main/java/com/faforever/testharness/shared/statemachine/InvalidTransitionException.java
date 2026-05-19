package com.faforever.testharness.shared.statemachine;

/** An exception caused by an invalid/non-existent transition. */
public class InvalidTransitionException extends RuntimeException {
    /**
     * Constructs a new exception.
     *
     * @param message a message with the cause of the exception.
     */
    public InvalidTransitionException(String message) {
        super(message);
    }
}
