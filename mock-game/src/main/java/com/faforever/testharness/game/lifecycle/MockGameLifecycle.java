package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import com.faforever.testharness.game.gpgnet.GpgNetDispatcher;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.GpgNetSender;
import com.faforever.testharness.game.gpgnet.Peer;
import com.faforever.testharness.shared.statemachine.Event;
import com.faforever.testharness.shared.statemachine.FailedTransitionException;
import com.faforever.testharness.shared.statemachine.InvalidTransitionPolicy;
import com.faforever.testharness.shared.statemachine.State;
import com.faforever.testharness.shared.statemachine.StateMachine;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the entire lifecycle of the mock game, from creation, initialization, running, and
 * teardown.
 */
public final class MockGameLifecycle {

    /** Logger for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(MockGameLifecycle.class);

    /** The default timeout length for the GpgNet connection,. */
    private static final Duration DEFAULT_GPGNET_CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    /** A mapping of result strings to numerical scores. */
    private static final Map<String, Integer> SCORES =
            Map.of("victory", 10, "defeat", -10, "draw", 10);

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

    /** Receive messages from the GpgNet server. */
    private final GpgNetDispatcher gpgnetDispatcher;

    /** A timeout for the GpgNet connection. */
    private final Duration gpgnetConnectionTimeout;

    /** A copy of the configuration settings given to the mock game. */
    private final MockGameConfig config;

    /** A scheduler used to make certain transitions occur after a configurable delay. */
    private final ScheduledExecutorService scheduler;

    /** The delay before initiating a match after all configuration is done. */
    private final Duration launchDelay;

    /** The total duration of the match, after which it is ended. */
    private final Duration matchDuration;

    /**
     * A future that upon completion, drives the state machine to launch the match. Created by the
     * {@code scheduler}.
     */
    private Future launchFuture;

    /**
     * A future that upon completion, drives the state machine to end the match. Created by the
     * {@code scheduler}.
     */
    private Future matchEndFuture;

    /** A record of all connected peers. */
    private List<Peer> peers;

    /**
     * Status of the lifecycle. Used mainly to convert to a corresponding exit code. Initially
     * FAILED, failures set it to other values. A successful ENDED sets it to OK.
     */
    private ExitStatus status = ExitStatus.FAILED;

    /** Possible exit status of the lifecycle. */
    public enum ExitStatus {
        /** No issue with the lifecycle. */
        OK,
        /** The server has disconnected. */
        SERVER_CONNECTION_LOST,
        /** Could not establish initial connection with the server. */
        SERVER_NOT_CONNECTED,
        /** Generic failure, obtained when no other failure applies. */
        FAILED
    }

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
     * @param launchDelay the delay before initiating a match after all configuration is done, or
     *     {@code null} if it will be driven entirely manually.
     * @param matchDuration the total duration of the match, after which it is ended, or {@code
     *     null} if it will be driven entirely manually.
     */
    public MockGameLifecycle(
            MockGameConfig config,
            GpgNetConnection gpgnetServer,
            Duration launchDelay,
            Duration matchDuration) {
        this(config, gpgnetServer, DEFAULT_GPGNET_CONNECTION_TIMEOUT, launchDelay, matchDuration);
    }

    /**
     * Internal constructor for testing with shorter timeout durations.
     *
     * @param config the configuration options given to the mock game.
     * @param gpgnetServer a not-yet-connected connection to the GpgNet Server.
     * @param gpgnetConnectionTimeout the timeout to wait on a GpgNet connection for.
     * @param launchDelay the delay before initiating a match after all configuration is done, or
     *     {@code null} if it will be driven entirely manually.
     * @param matchDuration the total duration of the match, after which it is ended, or {@code
     *     null} if it will be driven entirely manually.
     */
    MockGameLifecycle(
            MockGameConfig config,
            GpgNetConnection gpgnetServer,
            Duration gpgnetConnectionTimeout,
            Duration launchDelay,
            Duration matchDuration) {
        this.config = config;
        this.gpgnet = gpgnetServer;
        this.gpgnetConnectionTimeout = gpgnetConnectionTimeout;
        // Only one delay should be scheduled at a time, so one thread is enough.
        // Uses a daemon thread, so that it doesn't keep the JVM up after the the main thread(s)
        // finish executing.
        this.scheduler =
                Executors.newScheduledThreadPool(
                        1,
                        r -> {
                            Thread t = new Thread(r, "game-scheduler");
                            t.setDaemon(true);
                            return t;
                        });
        this.launchDelay = launchDelay;
        this.matchDuration = matchDuration;

        // Create the sender and receiver objects from the server.
        this.gpgnetSender = new GpgNetSender(gpgnetServer);
        this.gpgnetDispatcher = new GpgNetDispatcher();
        this.gpgnet.onFrame(this.gpgnetDispatcher);

        this.peers = new ArrayList<>();

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
     * Instruct the lifecycle to launch the match. Ignored outside of the HOSTING and JOINING
     * states.
     */
    public void launchMatch() {
        LOG.info("Manually instructed to launch match");
        // Prevents the delayed future from firing if a manual launch is called.
        if (launchFuture != null && !launchFuture.isDone()) {
            launchFuture.cancel(true);
        }
        machine.receiveEvent(new LaunchMatch());
    }

    /** Instruct the lifecycle to end the match. Ignored outside of the LIVE state. */
    public void endMatch() {
        LOG.info("Manually instructed to end match");
        // Prevents the delayed future from firing if a manual end is called.
        if (matchEndFuture != null && !matchEndFuture.isDone()) {
            matchEndFuture.cancel(true);
        }
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
     * Gets the exit status of the lifecycle.
     *
     * @return the exit status.
     * @throws IllegalStateException if called before the lifecycle reaches ENDED.
     */
    public ExitStatus getExitStatus() {
        if (machine.getState() != states.get(GameState.ENDED)) {
            throw new IllegalStateException(
                    "Tried to get the exit status before lifecycle has ENDED");
        }
        return status;
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
                .registerTransition(
                        LaunchMatch.class, states.get(GameState.LIVE), this::matchBegins, null);
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

        // Error transitions anywhere but ENDED
        GameState[] fromStates = {
            GameState.INITIALIZING,
            GameState.IDLE,
            GameState.LOBBY,
            GameState.HOSTING,
            GameState.JOINING,
            GameState.LIVE
        };
        for (var s : fromStates) {
            states.get(s).registerTransition(PeerDisconnected.class, states.get(GameState.ENDED));
            // Go to ENDED state when the server disconnects and it wasn't due to our shutdown
            // sequence.
            // Also set the correct status.
            states.get(s)
                    .registerTransition(
                            ServerDisconnected.class,
                            states.get(GameState.ENDED),
                            event -> {
                                switch (((ServerDisconnected) event).reason()) {
                                    case REMOTE_CLOSE:
                                        status = ExitStatus.SERVER_CONNECTION_LOST;
                                        break;
                                    case CONNECT_FAILED:
                                        status = ExitStatus.SERVER_NOT_CONNECTED;
                                        break;
                                    default:
                                        break;
                                }
                            },
                            event ->
                                    ((ServerDisconnected) event).reason()
                                            != DisconnectReason.LOCAL_CLOSE);
        }

        // Set gpgnet message transitions.
        gpgnetDispatcher.registerHandler(
                "CreateLobby", frame -> machine.receiveEvent(new CreateLobby(frame)));
        gpgnetDispatcher.registerHandler(
                "HostGame", ignored -> machine.receiveEvent(new HostGame()));
        gpgnetDispatcher.registerHandler(
                "JoinGame", frame -> machine.receiveEvent(new JoinGame(frame)));
        gpgnetDispatcher.registerHandler(
                "ConnectToPeer", frame -> machine.receiveEvent(new ConnectToPeer(frame)));

        gpgnet.onDisconnect(event -> machine.receiveEvent(new ServerDisconnected(event.reason())));

        // Shutdown sequence
        GameShutdown shutdown = new GameShutdown(machine, gpgnet);
        states.get(GameState.ENDED).onEntry(shutdown::run);

        // Start connection to GpgNet server and set a timeout if it doesn't occur.
        machine.setTimeout(
                gpgnetConnectionTimeout.toMillis(),
                states.get(GameState.ENDED),
                ignored -> {
                    status = ExitStatus.SERVER_NOT_CONNECTED;
                });
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
                        port,
                        config.lobbyPort());
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

        try {
            // Values for these will be 1 (peers list will be empty), but written like this for
            // consistency and avoiding magic numbers.
            gpgnetSender.playerOption(config.playerId(), "Army", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "Team", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "StartSpot", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "Faction", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "Color", peers.size() + 1);
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }

        // Set up the scheduler if configured.
        if (launchDelay != null) {
            scheduler.schedule(
                    () -> machine.receiveEvent(new LaunchMatch()),
                    launchDelay.toMillis(),
                    TimeUnit.MILLISECONDS);
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
            String login = frame.stringArg(1);
            int playerId = frame.intArg(2);
            LOG.info(
                    "Joining game from host ({}, ID: {}) with address {}",
                    login,
                    playerId,
                    address);
            peers.add(new Peer(address, login, playerId));
            // TODO: Initiate actual connection with peer
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.error("JoinGame frame did not have an IP address argument");
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }

        // Set up the scheduler if configured.
        if (launchDelay != null) {
            scheduler.schedule(
                    () -> machine.receiveEvent(new LaunchMatch()),
                    launchDelay.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /* Transition action for HOSTING/JOINING -> LIVE. */
    private void matchBegins(Event event) throws FailedTransitionException {
        try {
            gpgnetSender.gameState("Launching");
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }

        // Set up the scheduler if configured.
        if (matchDuration != null) {
            scheduler.schedule(
                    () -> machine.receiveEvent(new GameEnded()),
                    matchDuration.toMillis(),
                    TimeUnit.MILLISECONDS);
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
        Peer peer;
        try {
            String address = frame.stringArg(0);
            String login = frame.stringArg(1);
            int playerId = frame.intArg(2);
            LOG.info(
                    "New peer ({}, ID: {}) with address {}, attempting connection now",
                    login,
                    playerId,
                    address);
            peer = new Peer(address, login, playerId);
            peers.add(peer);
            // TODO: Initiate actual connection with peer
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.error("ConnectToPeer frame did not have an IP address argument");
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }

        if (getState() == GameState.HOSTING) {
            try {
                // First peer assigned number 2, then 3, and so on.
                // Each player is in a team of 1, i.e. free-for-all.
                gpgnetSender.playerOption(peer.playerId(), "Army", peers.size() + 1);
                gpgnetSender.playerOption(peer.playerId(), "Team", peers.size() + 1);
                gpgnetSender.playerOption(peer.playerId(), "StartSpot", peers.size() + 1);
                gpgnetSender.playerOption(peer.playerId(), "Faction", peers.size() + 1);
                gpgnetSender.playerOption(peer.playerId(), "Color", peers.size() + 1);
            } catch (IOException e) {
                throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
            }
        }
    }

    /* Transition action for LIVE -> ENDED. */
    private void gameEnds(Event event) throws FailedTransitionException {
        try {
            // TODO: Configurable values.
            gpgnetSender.gameResult(1, "victory", SCORES.get("victory"));
            for (int i = 2; i <= peers.size() + 1; i++) {
                gpgnetSender.gameResult(i, "defeat", SCORES.get("defeat"));
            }
            gpgnetSender.jsonStats("{\"stats\": []}");
            gpgnetSender.gameEnded();
            gpgnetSender.gameState("Ended");
        } catch (IOException e) {
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }

        // This marks the end of the lifecycle through the correct/successful path.
        status = ExitStatus.OK;
    }
}
