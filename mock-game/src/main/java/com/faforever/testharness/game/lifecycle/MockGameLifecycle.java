package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetDispatcher;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.GpgNetSender;
import com.faforever.testharness.shared.statemachine.Event;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.InvalidTransitionPolicy;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the entire lifecycle of the mock game, from creation, initialization, running, and
 * teardown.
 */
public final class MockGameLifecycle {

    /** Logger for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(MockGameLifecycle.class);

    /** The default timeout length for the GpgNet connection, in milliseconds. */
    private static final int DEFAULT_GPGNET_CONNECTION_TIMEOUT = 30 * 1000;

    /**
     * A delay to wait before sending messages to the GpgNet server on first connection, in
     * milliseconds. Necessary due to a race condition on the adapter.
     */
    private static final int GPGNET_CONNECTION_WAIT = 500;

    /** The internal state machine driving transitions. */
    private final StateMachine machine;

    /** Connection to the GpgNet server. */
    private final GpgNetConnection gpgnet;

    /** Send messages to the GpgNet server. */
    private final GpgNetSender gpgnetSender;

    /** Receive messages from the GpgNet serveer. */
    private final GpgNetDispatcher gpgnetDispatcher;

    /** A timeout for the GpgNet connection, in milliseconds. */
    private final int gpgnetConnectionTimeout;

    /** A copy of the configuration settings given to the mock game. */
    private final MockGameConfig config;

    /**
     * A mapping from the {@link GameState} enum to the actual state objects used by {@code
     * machine}.
     */
    private final Map<GameState, State> states;

    /**
     * Constructs a lifecycle object.
     *
     * @param config the configuration options given to the mock game.
     * @param gpgnetServer a not-yet-connected connection to the GpgNet Server.
     */
    public MockGameLifecycle(MockGameConfig config, GpgNetConnection gpgnetServer) {
        this(config, gpgnetServer, DEFAULT_GPGNET_CONNECTION_TIMEOUT);
    }

    /**
     * Internal constructor for testing with shorter timeout durations.
     *
     * @param config the configuration options given to the mock game.
     * @param gpgnetServer a not-yet-connected connection to the GpgNet Server.
     * @param gpgnetConnectionTimeout the timeout to wait on a GpgNet connection for, in
     *     milliseconds.
     */
    MockGameLifecycle(
            MockGameConfig config, GpgNetConnection gpgnetServer, int gpgnetConnectionTimeout) {
        this.config = config;
        this.gpgnet = gpgnetServer;
        this.gpgnetConnectionTimeout = gpgnetConnectionTimeout;

        // Create the sender and receiver objects from the server.
        this.gpgnetSender = new GpgNetSender(gpgnetServer);
        this.gpgnetDispatcher = new GpgNetDispatcher();
        this.gpgnet.onFrame(this.gpgnetDispatcher);

        this.states = new HashMap<>();
        for (var s : GameState.values()) {
            states.put(s, new State(s.toString()));
        }

        this.machine =
                new StateMachine(
                        states.get(GameState.INITIALIZING), InvalidTransitionPolicy.IGNORE);

        setupStateMachine();
    }

    /**
     * Instruct the lifecycle to launch the match. Idempotent outside of the LAUNCHING and JOINING
     * states.
     */
    public void launchMatch() {
        LOG.info("Launching match");
        machine.receiveEvent(new LaunchMatch());
    }

    /** Instruct the lifecycle to end the match. Idempotent outside of the LIVE state. */
    public void endMatch() {
        LOG.info("Ending match");
        machine.receiveEvent(new GameEnded());
    }

    /**
     * Gets the current state of the lifecycle.
     *
     * @return the current state.
     */
    public GameState getState() {
        return GameState.valueOf(machine.getState().getName());
    }

    /**
     * Gives a future that completes when the state is reached.
     *
     * @param state the state to wait for.
     * @return a future that only completes when the state is reached.
     */
    public CompletableFuture<Void> stateReached(GameState state) {
        return machine.stateReached(states.get(state));
    }

    private void setupStateMachine() {
        // Set up state transitions
        states.get(GameState.INITIALIZING)
                .registerTransition(
                        ServerConnected.class,
                        states.get(GameState.IDLE),
                        this::gpgnetConnected,
                        null);
        states.get(GameState.IDLE)
                .registerTransition(
                        CreateLobby.class, states.get(GameState.LOBBY), this::createLobby, null);
        states.get(GameState.LOBBY)
                .registerTransition(
                        HostGame.class, states.get(GameState.HOSTING), this::beginHosting, null);
        states.get(GameState.LOBBY)
                .registerTransition(
                        JoinGame.class, states.get(GameState.JOINING), this::joinGame, null);
        states.get(GameState.HOSTING)
                .registerTransition(
                        LaunchMatch.class, states.get(GameState.LIVE), this::matchBegins, null);
        states.get(GameState.JOINING)
                .registerTransition(LaunchMatch.class, states.get(GameState.LIVE));
        states.get(GameState.LIVE)
                .registerTransition(
                        GameEnded.class, states.get(GameState.ENDED), this::gameEnds, null);

        // Allow more peer connections while in HOSTING or JOINING states.
        states.get(GameState.HOSTING)
                .registerTransition(
                        ConnectToPeer.class,
                        states.get(GameState.HOSTING),
                        this::peerConnectionRequest,
                        null);
        states.get(GameState.JOINING)
                .registerTransition(
                        ConnectToPeer.class,
                        states.get(GameState.JOINING),
                        this::peerConnectionRequest,
                        null);

        // Error transitions
        GameState[] fromStates = {
            GameState.INITIALIZING,
            GameState.IDLE,
            GameState.LOBBY,
            GameState.HOSTING,
            GameState.JOINING
        };
        for (var s : fromStates) {
            states.get(s).registerTransition(PeerDisconnected.class, states.get(GameState.ENDED));
            states.get(s).registerTransition(ServerDisconnected.class, states.get(GameState.ENDED));
        }

        // Set gpgnet message transitions.
        gpgnetDispatcher.registerHandler(
                "CreateLobby", frame -> machine.receiveEvent(new CreateLobby(frame)));
        gpgnetDispatcher.registerHandler(
                "HostGame", ignored -> machine.receiveEvent(new HostGame()));
        gpgnetDispatcher.registerHandler(
                "JoinGame", frame -> machine.receiveEvent(new JoinGame(frame)));

        gpgnet.onDisconnect(ignored -> machine.receiveEvent(new ServerDisconnected()));

        // Start connection to GpgNet server and set a timeout if it doesn't occur.
        machine.setTimeout(gpgnetConnectionTimeout, states.get(GameState.ENDED));
        gpgnet.connect().thenRun(() -> machine.receiveEvent(new ServerConnected()));
    }

    /* Transition action for INITIALIZING -> IDLE. */
    private void gpgnetConnected(Event event) throws FailedTransitionException {
        LOG.info("Successful connection with GpgNet server established");
        try {
            // Wait some time before sending the first message.
            Thread.sleep(GPGNET_CONNECTION_WAIT);
            gpgnetSender.gameState("Idle");
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }
    }

    /* Transition action for IDLE -> LOBBY. */
    private void createLobby(Event event) throws FailedTransitionException {
        if (!(event instanceof CreateLobby)) {
            throw new AssertionError(
                    "createLobby called without a CreateLobby event, should be impossible");
        }
        GpgNetFrame frame = ((CreateLobby) event).frame();

        try {
            // Check whether the port given matches the one given as an argument.
            // The argument is the authoritative source, but we log when this one doesn't match as a
            // warning.
            int port = frame.intArg(1);
            if (port != config.lobbyPort()) {
                LOG.warn(
                        "CreateLobby lobby port ({}) differ "
                                + "from config lobby port ({}), might cause connection issues",
                        config.lobbyPort(),
                        port);
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.warn("CreateLobby frame did not have a GpgNet port argument");
        }

        try {
            gpgnetSender.gameState("Lobby");
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }
    }

    /* Transition action for LOBBY -> HOST. */
    private void beginHosting(Event event) throws FailedTransitionException {
        LOG.info("Setting up game as host");
        // TODO: Game options here
    }

    /* Transition action for HOST -> LIVE. */
    private void matchBegins(Event event) throws FailedTransitionException {
        try {
            gpgnetSender.gameState("Launching");
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }
    }

    /* Transition action for LOBBY -> JOIN. */
    private void joinGame(Event event) throws FailedTransitionException {
        LOG.info("Setting up game as joiner");
        if (!(event instanceof JoinGame)) {
            throw new AssertionError(
                    "joinGame called without a JoinGame event, should be impossible");
        }
        GpgNetFrame frame = ((JoinGame) event).frame();
        try {
            String address = frame.stringArg(0);
            LOG.info("Joining game from host with address {}", address);
            // TODO: Use the address to connect to a peer.
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.error("JoinGame frame did not have an IP address argument");
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }
    }

    /* Transition action for peer request messages. */
    private void peerConnectionRequest(Event event) throws FailedTransitionException {
        if (!(event instanceof ConnectToPeer)) {
            throw new AssertionError(
                    "peerConnectionRequest called without a ConnectToPeer event, "
                            + "should be impossible");
        }
        GpgNetFrame frame = ((ConnectToPeer) event).frame();
        try {
            String address = frame.stringArg(0);
            LOG.info("New peer with address {}, attempting connection now", address);
            // TODO: Use the address to connect to a peer.
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.error("ConnectToPeer frame did not have an IP address argument");
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }
    }

    /* Transition action for LIVE -> ENDED. */
    private void gameEnds(Event event) throws FailedTransitionException {
        try {
            gpgnetSender.gameState("Ended");
            // TODO: Configurable values.
            gpgnetSender.gameResult(1, "victory 10");
            // TODO: Actual json
            gpgnetSender.jsonStats("");
            gpgnetSender.gameEnded();
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }
    }
}
