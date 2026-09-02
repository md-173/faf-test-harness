package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetConnection.DisconnectReason;
import com.faforever.testharness.game.gpgnet.GpgNetDispatcher;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.GpgNetSender;
import com.faforever.testharness.game.gpgnet.Peer;
import com.faforever.testharness.game.net.GameTrafficSession;
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

    /** The one shutdown sequence for this game; see {@link #shutdown()}. */
    private final GameShutdown shutdown;

    /**
     * This game's peer traffic (WBS-4.3.2): the lobby socket, the UDP sender and receiver over it,
     * and the progress line. Driven from three transition actions below — bound on {@code
     * CreateLobby}, given a destination on {@code JoinGame} / {@code ConnectToPeer} — and closed by
     * the shutdown sequence, which is the only thing that stops it.
     */
    private final GameTrafficSession traffic;

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
     * Status of the lifecycle, to be mapped to a process exit code by the bootstrap (WBS-3.2.5.1).
     * That mapping does not exist yet — {@link com.faforever.testharness.game.config.ExitCodes}
     * currently defines no code for SERVER_CONNECTION_LOST. See {@link #getExitStatus()} for what
     * this does and does not claim.
     *
     * <p>Starts FAILED so that any path reaching ENDED without a deliberate assignment — including
     * a transition action that throws its way there — reports failure rather than success.
     *
     * <p>Volatile only to keep {@link #getExitStatus()} self-contained. Every write already happens
     * under the state machine's monitor and is program-ordered before the volatile {@code state}
     * write that publishes it, so the read is safe without this — but only because getExitStatus
     * reads {@code state} before {@code status}. That ordering should not be load-bearing.
     */
    private volatile ExitStatus status = ExitStatus.FAILED;

    /** Possible exit status of the lifecycle. */
    public enum ExitStatus {
        /**
         * The lifecycle ran its own program to completion: every frame the mock game owes was
         * handed to the transport without error. Not a claim that those frames were delivered — see
         * {@link MockGameLifecycle#getExitStatus()}.
         */
        OK,
        /**
         * The GPGNet connection was established and then lost: either the reader observed the
         * close, or a send failed because the socket was already gone.
         */
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
        this.shutdown = new GameShutdown(machine, gpgnet);

        // Assigned here rather than at the field, which would read a still-null config. Registered
        // with the shutdown sequence immediately: that sequence is the only thing that stops the
        // cadence and closes the socket, on every exit path including a SIGTERM before CreateLobby.
        this.traffic = new GameTrafficSession(config.playerId());
        this.shutdown.registerTrafficSession(traffic);

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
     * <p>This reports whether <em>this process completed its own program</em>, not whether the
     * match ended cleanly at the far end. The two differ, and the difference is not resolvable
     * here: {@link GpgNetConnection#send} returns once the kernel accepts the bytes, so when the
     * adapter dies as the match ends, the closing frames can all be written into a dead socket's
     * send buffer without error and leave the status {@link ExitStatus#OK} having delivered
     * nothing. Neither the socket API nor GPGNet gives the emitter a delivery test — the write call
     * reports only local acceptance, and the protocol has no application-level ack for the closing
     * frames. Deriving OK from the connection still looking live after the writes would only move
     * the race, since FIN and RST arrive asynchronously and it is the read loop's EOF handling that
     * actually closes the socket.
     *
     * <p>So this side does not pretend to have such a test. The authoritative clean-end signal is
     * the observer's: {@code MockClientLifecycle.isCleanEndSeen} records the {@code GameEnded}
     * frame as forwarded by the adapter, confirming delivery at the far end, which is where a false
     * OK here gets contradicted. Per the analysis in issue #277, that split mirrors the real
     * client, whose {@code GameRunner.handleTermination} treats the exit code as the crash signal
     * and reports end-of-game separately rather than from the game's own frames.
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
     * This game's shutdown sequence (WBS-3.2.5.2), already wired to run on entry to ENDED.
     *
     * <p>Exposed so the bootstrap can install the <em>same</em> instance as the JVM shutdown hook
     * rather than build a second one: the sequence is once-guarded, so a self-initiated exit and a
     * {@code SIGTERM} converge on it with no double-teardown. It is safe to run at any phase,
     * including before the GPGNet connection ever opened.
     *
     * <p>Running it out of band does not end the lifecycle. It closes the connection and cancels
     * the FSM's scheduling without posting any event, so the machine stays in whatever state it was
     * in and {@link #stateReached(GameState)} for ENDED never completes. Callers that want the FSM
     * driven to ENDED should let it get there on its own, or on a disconnect; this is teardown for
     * a process that is already on its way out.
     *
     * @return the shutdown sequence; never {@code null}.
     */
    public GameShutdown shutdown() {
        return shutdown;
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

        // A local close is our own shutdown sequence closing the socket, never news to the FSM: the
        // transition guard below rejects it in every state, and in ENDED — where the shutdown
        // sequence runs — there is no ServerDisconnected transition at all, so posting it there
        // logged "No matching transitions" on every clean exit. Filter it at the source instead.
        gpgnet.onDisconnect(
                event -> {
                    if (event.reason() != DisconnectReason.LOCAL_CLOSE) {
                        machine.receiveEvent(new ServerDisconnected(event.reason()));
                    }
                });

        // Shutdown sequence, also handed to the bootstrap as its JVM shutdown hook (WBS-3.2.5.1)
        // so a self-initiated exit and a SIGTERM converge on the same once-guarded instance.
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

    /**
     * Records a failed GPGNet send as connection loss and builds the transition failure into ENDED.
     *
     * <p>Every transition action that sends does so through the one socket, and in every situation
     * this lifecycle can reach, {@link GpgNetConnection#send} fails because that socket is already
     * gone — so a send failure is connection loss in whichever phase it happens, never a generic
     * fault. (Its other two failure modes are unreachable from here: a null stream cannot occur,
     * because no action sends before the connect future completes and publishes it, and the
     * over-cap frame check throws IllegalArgumentException, which this does not catch.) Without
     * this the status would keep its initial FAILED, because throwing into ENDED skips the
     * assignment at the end of the action; the same physical event would then report
     * SERVER_CONNECTION_LOST when the reader thread noticed the close first and FAILED when the
     * send did.
     *
     * <p>Parse failures are deliberately not routed here: a malformed inbound frame is a real
     * generic failure and keeps FAILED.
     *
     * @param e the send failure.
     * @return the exception for the caller to throw.
     */
    private FailedTransitionException recordSendFailure(IOException e) {
        status = ExitStatus.SERVER_CONNECTION_LOST;
        return new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
    }

    /* Transition action for INITIALIZING -> IDLE. */
    private void gpgnetConnected(Event event) throws FailedTransitionException {
        LOG.info("Successful connection with GpgNet server established");
        try {
            // Wait some time before sending the first message.
            Thread.sleep(GPGNET_CONNECTION_WAIT);
            gpgnetSender.gameState("Idle");
        } catch (IOException e) {
            throw recordSendFailure(e);
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

        // Bound before the frame below, not after: the lobby server marks the game hosted on
        // GameState Lobby, and from that moment a peer's datagrams can arrive at this port.
        traffic.bind(config.lobbyPort());

        try {
            gpgnetSender.gameState("Lobby");
        } catch (IOException e) {
            throw recordSendFailure(e);
        }
    }

    /* Transition action for LOBBY -> HOST. */
    private void beginHosting(Event event) throws FailedTransitionException {
        LOG.info("Setting up game as host");

        try {
            // Values for these will be 1 (peers list will be empty), but written like this for
            // consistency and avoiding magic numbers.
            gpgnetSender.playerOption(config.playerId(), "Army", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "Team", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "StartSpot", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "Faction", peers.size() + 1);
            gpgnetSender.playerOption(config.playerId(), "Color", peers.size() + 1);

            // No game options are required, but any could be passed to test different properties.
            for (var entry : config.gameOptions().entrySet()) {
                gpgnetSender.gameOption(entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            throw recordSendFailure(e);
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
            // The address is the host's relay socket inside our own adapter; sending to it is what
            // puts game traffic on the ICE path (WBS-4.3.2). The first peer starts the cadence.
            traffic.registerPeer(address, playerId);
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
            throw recordSendFailure(e);
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
            // As in joinGame: this peer's relay socket is where its share of our traffic goes.
            traffic.registerPeer(address, playerId);
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
                throw recordSendFailure(e);
            }
        }
    }

    /* Transition action for LIVE -> ENDED. */
    private void gameEnds(Event event) throws FailedTransitionException {
        try {
            // TODO(#281): Configurable values.
            gpgnetSender.gameResult(1, "victory", SCORES.get("victory"));
            for (int i = 2; i <= peers.size() + 1; i++) {
                gpgnetSender.gameResult(i, "defeat", SCORES.get("defeat"));
            }
            gpgnetSender.jsonStats("{\"stats\": []}");
            gpgnetSender.gameEnded();
            gpgnetSender.gameState("Ended");
        } catch (IOException e) {
            throw recordSendFailure(e);
        }

        // Every closing frame was handed to the transport without error, which is as much as this
        // side can establish: see getExitStatus() for why that is not proof they were delivered.
        status = ExitStatus.OK;
    }
}
