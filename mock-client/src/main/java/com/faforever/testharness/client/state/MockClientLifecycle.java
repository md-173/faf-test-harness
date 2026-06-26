package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.faforever.testharness.shared.statemachine.Event;
import com.faforever.testharness.shared.statemachine.InvalidTransitionPolicy;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracks the mock client's lifecycle, from connection to the lobby server until termination. */
public final class MockClientLifecycle {

    /** Logger for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(MockClientLifecycle.class);

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
    private CompletableFuture<SessionState> welcomeFuture;

    /** Maps the JSON result of LobbyConnection and LobbyHandshake into records. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Build and initialise the mock client's lifecycle. This constructor will set up all
     * transitions on the internal state machine and subscribe to all relevant events from lobby and
     * handshake.
     *
     * @param lobby an open connection to the lobby server.
     * @param handshake the object responsible for the initial handshake with the lobby server.
     */
    public MockClientLifecycle(LobbyConnection lobby, LobbyHandshake handshake) {
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
        states.get(ClientState.CONNECTING)
                .registerTransition(Disconnected.class, states.get(ClientState.TERMINATED));
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

        // Adapt lobby events to state events.
        lobby.onDisconnect(e -> machine.receiveEvent(new Disconnected(e)));
        lobby.registerHandler(
                "game_launch", message -> machine.receiveEvent(new LaunchGame(message)));
        lobby.registerHandler("HostGame", message -> machine.receiveEvent(new HostGame(message)));
        lobby.registerHandler("JoinGame", message -> machine.receiveEvent(new JoinGame(message)));
    }

    /**
     * Initiates handshake with the lobby server, which sets the entire lifecycle in motion.
     *
     * @param source a source for OAuth tokens for the handshake.
     */
    public void start(TokenSource source) {
        welcomeFuture =
                handshake
                        .perform(source)
                        .thenApply(node -> mapper.convertValue(node, WelcomeMessage.class))
                        .thenApply(SessionState::from)
                        .whenComplete(
                                (state, err) -> {
                                    if (err == null) {
                                        LOG.info("Handshake complete, transitioning to IDLE");
                                        machine.receiveEvent(new WelcomeReceived(state));
                                    } else {
                                        LOG.warn("Handshake could not be completed");
                                        machine.receiveEvent(new AuthFailed(err.getCause()));
                                    }
                                });
    }

    /**
     * Wait on the handshake to finish. Returns immediately if called before {@link
     * #start(TokenSource) start}.
     */
    public void awaitHandshake() throws InterruptedException, ExecutionException {
        if (welcomeFuture != null) {
            LOG.info("Waiting on lobby handshake to complete");
            welcomeFuture.get();
        }
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
        LOG.info("Manual shutdown requested");
        machine.receiveEvent(new ShutdownRequested());
    }

    /**
     * Directly forwards an event to the state machine. Used for testing.
     *
     * @param e the event to send.
     */
    /*package-private*/ void post(Event e) {
        machine.receiveEvent(e);
    }
}
