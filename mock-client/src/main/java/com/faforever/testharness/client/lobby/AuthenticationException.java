package com.faforever.testharness.client.lobby;

/** Represents errors during authentication token generation. */
public class AuthenticationException extends RuntimeException {

    /**
     * Creates an {@code AuthenticationException} with a message.
     *
     * @param message the detail message.
     */
    public AuthenticationException(String message) {
        super(message);
    }

    /**
     * Creates an {@code AuthenticationException} with a message and cause.
     *
     * @param message the detail message.
     * @param cause a cause for this exception occuring.
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
