package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.GameHostConfig;
import com.faforever.testharness.client.config.GameJoinConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.GameHostSender;
import com.faforever.testharness.client.lobby.GameJoinSender;
import com.faforever.testharness.client.lobby.GameLaunchHandler;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.lobby.TokenSource;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.client.process.SessionTeardown;
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
     * The lobby session — the lifecycle's single point of contact with the server. Owns the
     * transport, the auth handshake, and the welcome hydration; events raised by it are used to
     * transition from CONNECTING to IDLE.
     */
    private final LobbySession session;

    /**
     * The session's underlying transport. The lifecycle object listens to various messages from the
     * lobby and forwards them to the state machine.
     */
    private final LobbyConnection lobby;

    /** Config settings for the mock client. */
    private final MockClientConfig config;

    /** JSON-RPC connection to the ICE adapter. */
    private final IceAdapterConnection iceConnection;

    /** Dependency-injected mock game launcher used to start the game binary process. */
    private final MockGameLauncher gameLauncher;

    /** Dependency-injected ice adapter launcher used to start the ice adapter process. */
    private final IceAdapterLauncher iceLauncher;

    /**
     * Coordinated session teardown shared with the CLI's signal hook; the game subprocess is
     * registered with it at launch (WBS-3.1.2.4) so both shutdown paths can reach it.
     */
    private final SessionTeardown teardown;

    /** ICE adapter subprocess. */
    private SubprocessManager iceAdapter;

    /** Game binary subprocess. */
    private SubprocessManager gameBinary;

    /**
     * The session's single game-exit signal (WBS-3.1.2.4): completes with the game's exit code once
     * the process launched by {@link #launchGame} exits. Never completes if no game launches.
     */
    private final CompletableFuture<Integer> gameExit = new CompletableFuture<>();

    /** Maps the JSON result of LobbyConnection and LobbyHandshake into records. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Build and initialise the mock client's lifecycle. This constructor will set up all
     * transitions on the internal state machine and subscribe to all relevant events from the
     * session and its transport.
     *
     * @param config a set of configuration options passed by the user.
     * @param session a not-yet-started lobby session; {@link #start(TokenSource)} opens it.
     * @param teardown the session's coordinated teardown, shared with the CLI's signal hook.
     */
    public MockClientLifecycle(
            MockClientConfig config, LobbySession session, SessionTeardown teardown) {
        this(
                config,
                session,
                new IceAdapterConnection(config.iceAdapterRpcPort()),
                new MockGameLauncher(config),
                new IceAdapterLauncher(config),
                teardown);
    }

    /**
     * Constructor with all dependency-injected classes ({@code IceAdapterConnection}, {@code
     * MockGameLauncher}, and {@code IceAdapterLauncher}) available, used for testing with mock
     * versions of launchers and connection.
     *
     * @param config a set of configuration options passed by the user.
     * @param session a not-yet-started lobby session; {@link #start(TokenSource)} opens it.
     * @param iceConnection the ice adapter connection. {@link IceAdapterConnection#connect()}
     *     should not be called on this object yet.
     * @param gameLauncher spawns a mock game subprocess.
     * @param iceLauncher spawns an ice adapter subprocess.
     * @param teardown the session's coordinated teardown, shared with the CLI's signal hook.
     */
    MockClientLifecycle(
            MockClientConfig config,
            LobbySession session,
            IceAdapterConnection iceConnection,
            MockGameLauncher gameLauncher,
            IceAdapterLauncher iceLauncher,
            SessionTeardown teardown) {
        this.config = config;
        this.session = session;
        this.lobby = session.connection();
        this.iceConnection = iceConnection;
        this.gameLauncher = gameLauncher;
        this.iceLauncher = iceLauncher;
        this.teardown = teardown;

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
        states.get(ClientState.IDLE).onEntry(this::sendGameHostIfConfigured);
        states.get(ClientState.IDLE).onEntry(this::sendGameJoinIfConfigured);

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
     * Opens the session (connect + handshake + welcome hydration), which sets the entire lifecycle
     * in motion: success posts {@code WelcomeReceived} (CONNECTING → IDLE), failure posts {@code
     * AuthFailed} (CONNECTING → TERMINATED).
     *
     * @param source a source for OAuth tokens for the handshake.
     * @return the session's future, completing with the hydrated identity or exceptionally with the
     *     connect/handshake failure — callers may use it for precise error reporting while the
     *     state machine tracks the same outcome as events.
     */
    public CompletableFuture<SessionState> start(TokenSource source) {
        CompletableFuture<SessionState> result = session.start(source);
        result.whenComplete(
                (state, err) -> {
                    if (err == null) {
                        machine.receiveEvent(new WelcomeReceived(state));
                    } else {
                        LOG.warn("Handshake could not be completed");
                        machine.receiveEvent(
                                new AuthFailed(err.getCause() != null ? err.getCause() : err));
                    }
                });
        return result;
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

    /**
     * The session's single game-exit signal: completes exactly once with the game process's exit
     * code, whether it exited cleanly or was killed. Safe to call at any time — before launch,
     * while the game runs, or after exit (a copy of an already-completed future is already
     * complete, so late subscribers still observe the code). If no game ever launches, the future
     * never completes.
     *
     * <p>Each call returns an independent copy; cancelling or completing it does not affect other
     * subscribers. Continuations run on the JDK's exit-completion thread — hand non-trivial work
     * to your own executor.
     *
     * @return a future resolving to the game's exit code
     */
    public CompletableFuture<Integer> gameExit() {
        return gameExit.copy();
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
            // Single ownership of the game process (WBS-3.1.2.4): register it for coordinated
            // teardown and fan its exit code into the session's one exit signal. Consumers
            // subscribe via gameExit(); nothing else touches the manager's onExit.
            teardown.registerGameProcess(gameBinary);
            gameBinary.onExit().thenAccept(gameExit::complete);
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
     * IDLE entry hook: sends {@code game_host} for {@link MockClientConfig#hostConfig()}
     * (lobby-protocol-spec.md §4.1 / §10.2). No-op if no host settings were configured for this
     * session — the mock client hosts, joins, or sits idle depending on what the operator
     * configured.
     */
    private void sendGameHostIfConfigured() {
        if (config.hostConfig().isEmpty()) {
            return;
        }
        GameHostConfig hostConfig = config.hostConfig().get();
        LOG.info("Sending game_host for title={}", hostConfig.title());
        new GameHostSender(lobby).sendGameHost(hostConfig);
    }

    /**
     * IDLE entry hook: sends {@code game_join} for {@link MockClientConfig#joinConfig()}
     * (lobby-protocol-spec.md §4.2 / §10.2). No-op if no target game was configured for this
     * session — the mock client hosts, joins, or sits idle depending on what the operator
     * configured.
     */
    private void sendGameJoinIfConfigured() {
        if (config.joinConfig().isEmpty()) {
            return;
        }
        GameJoinConfig joinConfig = config.joinConfig().get();
        LOG.info("Sending game_join for uid={}", joinConfig.targetGameId());
        new GameJoinSender(lobby).sendGameJoin(joinConfig);
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
