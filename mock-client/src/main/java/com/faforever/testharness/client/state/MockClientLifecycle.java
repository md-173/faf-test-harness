package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.GameLaunchHandler;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbyHandshake;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.faforever.testharness.shared.statemachine.Event;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.InvalidTransitionPolicy;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
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

    /** Config settings for the mock client. */
    private final MockClientConfig config;

    /** JSON-RPC connection to the ICE adapter. */
    private final IceAdapterConnection iceConnection;

    /** Dependency-injected mock game launcher used to start the game binary process. */
    private final MockGameLauncher gameLauncher;

    /** Dependency-injected ice adapter launcher used to start the ice adapter process. */
    private final IceAdapterLauncher iceLauncher;

    /** ICE adapter subprocess. */
    private SubprocessManager iceAdapter;

    /** Game binary subprocess. */
    private SubprocessManager gameBinary;

    /** Maps the JSON result of LobbyConnection and LobbyHandshake into records. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Build and initialise the mock client's lifecycle. This constructor will set up all
     * transitions on the internal state machine and subscribe to all relevant events from lobby and
     * handshake.
     *
     * @param config a set of configuration options passed by the user.
     * @param lobby an open connection to the lobby server.
     * @param handshake the object responsible for the initial handshake with the lobby server.
     */
    public MockClientLifecycle(
            MockClientConfig config, LobbyConnection lobby, LobbyHandshake handshake) {
        this(
                config,
                lobby,
                handshake,
                new IceAdapterConnection(config.iceAdapterRpcPort()),
                new MockGameLauncher(config),
                new IceAdapterLauncher(config));
    }

    /**
     * Constructor with all dependency-injected classes ({@code IceAdapterConnection}, {@code
     * MockGameLauncher}, and {@code IceAdapterLauncher}) available, used for testing with mock
     * versions of launchers and connection.
     *
     * @param config a set of configuration options passed by the user.
     * @param lobby an open connection to the lobby server.
     * @param handshake the object responsible for the initial handshake with the lobby server.
     * @param iceConnection the ice adapter connection. {@link IceAdapterConnection#connect()}
     *     should not be called on this object yet.
     * @param gameLauncher spawns a mock game subprocess.
     * @param iceLauncher spawns an ice adapter subprocess.
     */
    MockClientLifecycle(
            MockClientConfig config,
            LobbyConnection lobby,
            LobbyHandshake handshake,
            IceAdapterConnection iceConnection,
            MockGameLauncher gameLauncher,
            IceAdapterLauncher iceLauncher) {
        this.config = config;
        this.lobby = lobby;
        this.handshake = handshake;
        this.iceConnection = iceConnection;
        this.gameLauncher = gameLauncher;
        this.iceLauncher = iceLauncher;

        states = new HashMap<>();
        for (var s : ClientState.values()) {
            states.put(s, new State(s.toString()));
        }
        machine =
                new StateMachine(
                        states.get(ClientState.CONNECTING), InvalidTransitionPolicy.IGNORE);
        setupStateMachine();
    }

    private void setupStateMachine() {
        // Transitions between states, caused by internal events.
        // TODO: No transition logic yet.
        states.get(ClientState.CONNECTING)
                .registerTransition(WelcomeReceived.class, states.get(ClientState.IDLE));
        states.get(ClientState.CONNECTING)
                .registerTransition(AuthFailed.class, states.get(ClientState.TERMINATED));

        states.get(ClientState.IDLE)
                .registerTransition(
                        LaunchGame.class,
                        states.get(ClientState.STARTING_GAME),
                        this::launchGame,
                        null);

        states.get(ClientState.STARTING_GAME)
                .registerTransition(
                        HostGame.class, states.get(ClientState.HOSTING), this::hostGame, null);
        states.get(ClientState.STARTING_GAME)
                .registerTransition(
                        JoinGame.class, states.get(ClientState.JOINING), this::joinGame, null);

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
        GameLaunchHandler launchHandler =
                new GameLaunchHandler(
                        mapper, message -> machine.receiveEvent(new LaunchGame(message)));
        lobby.registerHandler("game_launch", launchHandler::onMessage);
        lobby.registerHandler("HostGame", message -> machine.receiveEvent(new HostGame(message)));
        lobby.registerHandler("JoinGame", message -> machine.receiveEvent(new JoinGame(message)));
    }

    /**
     * Initiates handshake with the lobby server, which sets the entire lifecycle in motion.
     *
     * @param source a source for OAuth tokens for the handshake.
     */
    public void start(TokenSource source) {
        handshake
                .perform(source)
                .thenApply(node -> mapper.convertValue(node, WelcomeMessage.class))
                .thenApply(SessionState::from)
                .whenComplete(
                        (state, err) -> {
                            if (err == null) {
                                machine.receiveEvent(new WelcomeReceived(state));
                            } else {
                                LOG.warn("Handshake could not be completed");
                                machine.receiveEvent(new AuthFailed(err.getCause()));
                            }
                        });
    }

    /**
     * Gives a future that completes when the state is reached.
     *
     * @param state the state to wait for.
     * @return a future that only completes when the state is reached.
     */
    public CompletableFuture<Void> stateReached(ClientState state) {
        return machine.stateReached(states.get(state));
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

    private void launchGame(Event message) throws FailedTransitionException {
        if (!(message instanceof LaunchGame)) {
            throw new AssertionError(
                    "launchGame method called without a LaunchGame event, should be impossible");
        }
        GameConfig gameConfig = ((LaunchGame) message).config();
        try {
            iceAdapter = iceLauncher.start();
            iceConnection.connect().get();
            iceConnection
                    .call(
                            "setLobbyInitMode",
                            gameConfig.gameType().equals("matchmaker") ? "auto" : "normal")
                    .get();
            // Empty list of ICE servers, so only public STUN servers will be used.
            iceConnection.call("setIceServers", new Object[0]).get();
            gameBinary = gameLauncher.start();
        } catch (IceAdapterLaunchException e) {
            LOG.warn("Could not launch the ICE adapter ({})", e.getMessage());
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (CancellationException | ExecutionException e) {
            LOG.warn("Could not connect or setup the ICE adapter ({})", e.getMessage());
            iceAdapter.terminate();
            iceConnection.close();
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            iceAdapter.terminate();
            iceConnection.close();
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (MockGameLaunchException e) {
            LOG.warn("Could not launch game binary ({})", e.getMessage());
            iceAdapter.terminate();
            iceConnection.close();
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        }
    }

    private void hostGame(Event message) throws FailedTransitionException {
        if (!(message instanceof HostGame)) {
            throw new AssertionError(
                    "hostGame method called without a HostGame event, should be impossible");
        }
        JsonNode command = ((HostGame) message).command();
        JsonNode mapNode = command.path("args").path(0);
        if (!mapNode.isTextual()) {
            throw new FailedTransitionException(
                    "textual map argument not found in HostGame message",
                    states.get(ClientState.TERMINATED));
        }

        String map = mapNode.asText();
        try {
            iceConnection.call("hostGame", map).get();
        } catch (ExecutionException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        }
    }

    private void joinGame(Event message) throws FailedTransitionException {
        if (!(message instanceof JoinGame)) {
            throw new AssertionError(
                    "joinGame method called without a JoinGame event should be impossible");
        }
        JsonNode command = ((JoinGame) message).command();
        JsonNode remoteLogin = command.path("args").path(0);
        JsonNode remoteID = command.path("args").path(1);
        if (!remoteLogin.isTextual() || !remoteID.isInt()) {
            throw new FailedTransitionException(
                    "textual remote login and remote id arguments not found in JoinGame message",
                    states.get(ClientState.TERMINATED));
        }

        try {
            iceConnection.call("joinGame", remoteLogin.asText(), remoteID.asInt()).get();
        } catch (ExecutionException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        }
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
