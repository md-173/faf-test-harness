package com.faforever.testharness.shared.statemachine;

/** The policy on how the {@link StateMachine} should handle an invalid/non-existent transition. */
public enum InvalidTransitionPolicy {
    /** Policy for ignoring invalid transitions. */
    IGNORE,
    /** Policy for raising an exception on invalid transitions. */
    THROW
}
