package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.shared.statemachine.Event;
import java.util.Objects;

/**
 * Authentication with the lobby server was successful.
 *
 * @param state the session information given by the lobby server.
 */
/*package-private*/ record WelcomeReceived(SessionState state) implements Event {

    // Rejecting a null state is what lets MockClientLifecycle treat the session identity as always
    // present once IDLE is entered (WBS-3.1.2.9), since IDLE is reachable only through this event.
    WelcomeReceived {
        Objects.requireNonNull(state, "state");
    }
}
