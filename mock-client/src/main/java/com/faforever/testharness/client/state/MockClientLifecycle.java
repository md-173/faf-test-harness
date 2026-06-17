package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.faforever.testharness.shared.statemachine.InvalidTransitionPolicy;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Tracks the mock client's lifecycle, from connection to the lobby server until termination. */
public final class MockClientLifecycle {
    /** State machine used to produce behaviors from changes in state. */
    private final StateMachine machine;

    /** Maps each value in the ClientState enum to an actual state instance. */
    private final Map<ClientState, State> states;

    /**
     * Connection to lobby. The lifecycle object listens to various messages from the lobby and
     * forwards them to the state machine.
     */
    private final LobbyConnection lobby;

    /**
     * Performs the handshake with the lobby. Events raised by this object are used to transition
     * from CONNECTING to IDLE.
     */
    private final LobbyHandshake handshake;

    /** Future from LobbyHandshake with the welcome message. */
    private final CompletableFuture<SessionState> welcomeFuture;

    /** Maps the JSON result of LobbyConnection and LobbyHandshake into records. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Build and initialise the mock client's lifecycle. This constructor will set up all
     * transitions on the internal state machine and subscribe to all relevant events from lobby and
     * handshake.
     *
     * @param lobby an open connection to the lobby server.
     * @param handshake the object responsible for the initial handshake with the lobby server.
     * @param source a source for OAuth tokens for the handshake.
     */
    public MockClientLifecycle(
            LobbyConnection lobby, LobbyHandshake handshake, TokenSource source) {
        this.lobby = lobby;
        this.handshake = handshake;

        states = new HashMap<>();
        for (var s : ClientState.values()) {
            states.put(s, new State(s.toString()));
        }

        machine =
                new StateMachine(
                        states.get(ClientState.CONNECTING), InvalidTransitionPolicy.IGNORE);

        // Transitions between states, caused by internal events.
        // TODO: No transition logic yet.
        states.get(ClientState.CONNECTING)
                .registerTransition(WelcomeReceived.class, states.get(ClientState.IDLE));
        states.get(ClientState.CONNECTING)
                .registerTransition(AuthFailed.class, states.get(ClientState.TERMINATED));

        states.get(ClientState.IDLE)
                .registerTransition(LaunchGame.class, states.get(ClientState.STARTING_GAME));

        states.get(ClientState.STARTING_GAME)
                .registerTransition(HostGame.class, states.get(ClientState.HOSTING));
        states.get(ClientState.STARTING_GAME)
                .registerTransition(JoinGame.class, states.get(ClientState.JOINING));

        states.get(ClientState.HOSTING)
                .registerTransition(StartMatch.class, states.get(ClientState.PLAYING));
        states.get(ClientState.JOINING)
                .registerTransition(StartMatch.class, states.get(ClientState.PLAYING));

        // Disconnection on any of these states results in termination.
        states.get(ClientState.IDLE)
                .registerTransition(Disconnected.class, states.get(ClientState.TERMINATED));
        states.get(ClientState.STARTING_GAME)
                .registerTransition(Disconnected.class, states.get(ClientState.TERMINATED));
        states.get(ClientState.HOSTING)
                .registerTransition(Disconnected.class, states.get(ClientState.TERMINATED));
        states.get(ClientState.JOINING)
                .registerTransition(Disconnected.class, states.get(ClientState.TERMINATED));

        states.get(ClientState.PLAYING)
                .registerTransition(GameExited.class, states.get(ClientState.TERMINATED));

        // Manual shutdown valid from every state.
        for (var s : ClientState.values()) {
            states.get(s)
                    .registerTransition(
                            ShutdownRequested.class, states.get(ClientState.TERMINATED));
        }

        // Adapt handshake/lobby events to state events.
        welcomeFuture =
                handshake
                        .perform(source)
                        .thenApply(node -> mapper.convertValue(node, WelcomeMessage.class))
                        .thenApply(SessionState::from)
                        .whenComplete(
                                (state, err) ->
                                        machine.receiveEvent(
                                                err == null
                                                        ? new WelcomeReceived(state)
                                                        : new AuthFailed(err.getCause())));

        lobby.onDisconnect(e -> machine.receiveEvent(new Disconnected(e)));
        lobby.registerHandler(
                "game_launch", message -> machine.receiveEvent(new LaunchGame(message)));
        lobby.registerHandler("HostGame", message -> machine.receiveEvent(new HostGame(message)));
        lobby.registerHandler("JoinGame", message -> machine.receiveEvent(new JoinGame(message)));
    }

    /** Wait on the handshake to finish. */
    public void performHandshake() throws Exception {
        welcomeFuture.get();
    }

    /**
     * Gets the current state of the mock client's lifecycle.
     *
     * @return the current ClientState.
     */
    public ClientState getState() {
        return ClientState.valueOf(machine.getState().getName());
    }

    /** Performs the full shutdown of the lifecycle. Valid to call on any state. */
    public void shutdown() {
        machine.receiveEvent(new ShutdownRequested());
    }
}
