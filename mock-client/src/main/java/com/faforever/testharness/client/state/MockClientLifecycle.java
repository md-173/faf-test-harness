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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracks the mock client's lifecycle, from connection to the lobby server until termination. */
public final class MockClientLifecycle {

    /** Logger for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(MockClientLifecycle.class);

    /**
     * Bounded safety net for client end-of-session reporting (#192): if the game has not exited
     * this long after a clean {@code GameEnded} frame was observed, a hung game is assumed and
     * {@link ShutdownRequested} is posted so the harness is not left waiting forever. Teardown
     * still runs through the ordinary TERMINATED path (R59b, {@link
     * com.faforever.testharness.client.process.SessionTeardown}) — this only decides when to
     * trigger it, it is not a second teardown mechanism.
     */
    private static final Duration GAME_END_SAFETY_NET_WINDOW = Duration.ofSeconds(30);

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

    /**
     * The session's single game-exit signal (WBS-3.1.2.4): completes with the game's exit code once
     * the process launched by {@link #launchGame} exits. Never completes if no game launches.
     */
    private final CompletableFuture<Integer> gameExit = new CompletableFuture<>();

    /** Maps the JSON result of LobbyConnection and LobbyHandshake into records. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Set once an {@code onGpgNetMessageReceived("GameEnded", …)} notification is observed for this
     * session (#192). Read by crash classification (R41): an exit observed after this flag is set —
     * including a 143 from a teardown-initiated SIGTERM — is a clean end, not a crash.
     */
    private final AtomicBoolean cleanEndSeen = new AtomicBoolean(false);

    /** Backs the safety-net window; a daemon thread, one per lifecycle. */
    private final Timer safetyNetTimer = new Timer("game-end-safety-net", true);

    /** The pending safety-net task armed on {@code GameEnded}, if any; cancelled on game exit. */
    private volatile TimerTask safetyNetTask;

    /**
     * Effective safety-net window; {@link #GAME_END_SAFETY_NET_WINDOW} unless overridden by test.
     */
    private final Duration safetyNetWindow;

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
        this(
                config,
                session,
                iceConnection,
                gameLauncher,
                iceLauncher,
                teardown,
                GAME_END_SAFETY_NET_WINDOW);
    }

    /**
     * Full-control constructor — used by tests to shrink the #192 safety-net window for fast,
     * deterministic runs.
     *
     * @param config a set of configuration options passed by the user.
     * @param session a not-yet-started lobby session; {@link #start(TokenSource)} opens it.
     * @param iceConnection the ice adapter connection. {@link IceAdapterConnection#connect()}
     *     should not be called on this object yet.
     * @param gameLauncher spawns a mock game subprocess.
     * @param iceLauncher spawns an ice adapter subprocess.
     * @param teardown the session's coordinated teardown, shared with the CLI's signal hook.
     * @param safetyNetWindow how long to wait after {@code GameEnded} before requesting shutdown.
     */
    MockClientLifecycle(
            MockClientConfig config,
            LobbySession session,
            IceAdapterConnection iceConnection,
            MockGameLauncher gameLauncher,
            IceAdapterLauncher iceLauncher,
            SessionTeardown teardown,
            Duration safetyNetWindow) {
        this.config = config;
        this.session = session;
        this.lobby = session.connection();
        this.iceConnection = iceConnection;
        this.gameLauncher = gameLauncher;
        this.iceLauncher = iceLauncher;
        this.teardown = teardown;
        this.safetyNetWindow = safetyNetWindow;

        // Register Ice connection for teardown.
        teardown.registerAdapterRpc(iceConnection);

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

        // ICE adapter death (#214): no session survives an adapter exit to restart into (verified
        // against downlords-faf-client and java-ice-adapter — see class javadoc for this card),
        // so every post-launch state tears down rather than hanging. Pre-launch failures are
        // already handled by launchGame's own exception handling and never reach this event.
        states.get(ClientState.STARTING_GAME)
                .registerTransition(
                        AdapterExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onAdapterExited,
                        null);
        states.get(ClientState.HOSTING)
                .registerTransition(
                        AdapterExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onAdapterExited,
                        null);
        states.get(ClientState.JOINING)
                .registerTransition(
                        AdapterExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onAdapterExited,
                        null);
        states.get(ClientState.PLAYING)
                .registerTransition(
                        AdapterExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onAdapterExited,
                        null);

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
                .registerTransition(
                        GameExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onGameExited,
                        null);

        // Lobby loss during PLAYING (#193): the official client survives lobby loss mid-game —
        // FafServerAccessor auto-reconnects and the game is never killed, because established peer
        // connections are peer-to-peer and the lobby is only the signalling relay. The harness
        // defers reconnect (R40) but matches the "play on" half of that behaviour: log and stay,
        // no teardown. The session still ends deterministically through the mock game's own exit
        // (GameExited, above), so this doesn't strand the session. Every other state above tears
        // down on Disconnected because setup/negotiation genuinely cannot proceed without the
        // lobby — PLAYING is the one phase where it can.
        states.get(ClientState.PLAYING)
                .registerTransition(
                        Disconnected.class,
                        states.get(ClientState.PLAYING),
                        this::logLobbyLossDuringPlaying,
                        null);

        // Teardown closes the lobby, which fires onDisconnect -> Disconnected while already in
        // TERMINATED. The framework's IGNORE policy already prevents any error from this (a
        // generic WARN), but registering it explicitly turns that noise into a deliberate,
        // debug-level no-op instead of an unregistered-event warning, and guarantees teardown
        // does not run a second time (self-loop transitions skip entry/exit hooks).
        states.get(ClientState.TERMINATED)
                .registerTransition(
                        Disconnected.class,
                        states.get(ClientState.TERMINATED),
                        this::logSelfInflictedDisconnect,
                        null);

        // Manual shutdown valid from every state.
        for (var s : ClientState.values()) {
            states.get(s)
                    .registerTransition(
                            ShutdownRequested.class, states.get(ClientState.TERMINATED));
        }

        // Teardown subprocesses and connections.
        states.get(ClientState.TERMINATED).onEntry(() -> teardown.run());

        // Adapt lobby events to state events.
        lobby.onDisconnect(e -> machine.receiveEvent(new Disconnected(e)));
        GameLaunchHandler launchHandler =
                new GameLaunchHandler(
                        mapper, message -> machine.receiveEvent(new LaunchGame(message)));
        lobby.registerHandler("game_launch", launchHandler::onMessage);
        lobby.registerHandler("HostGame", message -> machine.receiveEvent(new HostGame(message)));
        lobby.registerHandler("JoinGame", message -> machine.receiveEvent(new JoinGame(message)));

        // Wire the game exiting to the appropriate event.
        gameExit.thenAccept(exitCode -> machine.receiveEvent(new GameExited(exitCode)));
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
     * subscribers. Continuations run on the JDK's exit-completion thread — hand non-trivial work to
     * your own executor.
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

    /**
     * Whether a clean {@code GameEnded} frame has been observed for this session (#192). Read by
     * crash classification (R41) to distinguish a clean exit — including a 143 from a
     * teardown-initiated SIGTERM — from an actual crash.
     *
     * @return {@code true} once {@code onGpgNetMessageReceived("GameEnded", …)} has been seen.
     */
    public boolean isCleanEndSeen() {
        return cleanEndSeen.get();
    }

    /**
     * Filtering consumer on the ICE-notification fan-out (R36): looks for {@code GameEnded} among
     * the GPGNet frames R72 forwards verbatim, records the clean-end flag, and arms the bounded
     * safety net. Ignores every other frame; sends and tears down nothing.
     *
     * @param notification the raw {@code onGpgNetMessageReceived} JSON-RPC notification.
     */
    private void onGpgNetMessage(JsonNode notification) {
        JsonNode params = notification.get("params");
        if (params == null || !params.isArray() || params.isEmpty()) {
            return;
        }
        if (!"GameEnded".equals(params.get(0).asText())) {
            return;
        }
        if (!cleanEndSeen.compareAndSet(false, true)) {
            // Duplicate GameEnded frame; already recorded and safety net already armed.
            return;
        }
        LOG.info("GameEnded observed; arming {} safety net", safetyNetWindow);
        TimerTask task =
                new TimerTask() {
                    @Override
                    public void run() {
                        LOG.warn(
                                "Game did not exit within {} of GameEnded; requesting shutdown",
                                safetyNetWindow);
                        machine.receiveEvent(new ShutdownRequested());
                    }
                };
        safetyNetTask = task;
        try {
            safetyNetTimer.schedule(task, safetyNetWindow.toMillis());
        } catch (IllegalStateException e) {
            // The timer was already cancelled by a game exit racing this notification; nothing
            // left to protect against.
            LOG.debug("Safety net not armed, lifecycle is already tearing down");
        }
    }

    /**
     * {@link GameExited} transition action for PLAYING → TERMINATED (#192): cancels any pending
     * safety-net task armed by {@link #onGpgNetMessage} now that the game has actually exited on
     * its own. No new FSM edge — this attaches to the transition that already exists.
     *
     * @param event the {@link GameExited} event that triggered this transition.
     */
    private void onGameExited(Event event) {
        TimerTask task = safetyNetTask;
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * {@link AdapterExited} transition action for STARTING_GAME/HOSTING/JOINING/PLAYING →
     * TERMINATED (#214): classifies the adapter's own exit the way the real client's {@code
     * IceAdapterImpl} does — INFO on a clean exit(0), WARN with the code otherwise — except once
     * this session's teardown has already started, since every clean run terminates the adapter
     * itself and that expected SIGTERM must not be logged as a crash. The TERMINATED entry hook
     * (registered in {@link #setupStateMachine()}) runs the actual teardown; this method only logs.
     *
     * @param event the {@link AdapterExited} event that triggered this transition.
     */
    private void onAdapterExited(Event event) {
        int exitCode = ((AdapterExited) event).exitCode();
        if (teardown.isTearingDown()) {
            LOG.debug("ICE adapter exited (code={}) during session teardown", exitCode);
        } else if (exitCode == 0) {
            LOG.info("ICE adapter terminated normally");
        } else {
            LOG.warn("ICE adapter exited abnormally (code={})", exitCode);
        }
    }

    private void launchGame(Event message) throws FailedTransitionException {
        if (!(message instanceof LaunchGame)) {
            throw new AssertionError(
                    "launchGame method called without a LaunchGame event, should be impossible");
        }
        GameConfig gameConfig = ((LaunchGame) message).config();
        try {
            SubprocessManager iceAdapter = iceLauncher.start();
            // Register adapter for teardown.
            teardown.registerAdapterProcess(iceAdapter);
            // #214: single detection channel for adapter death — the process exit. The RPC
            // connection's onDisconnect fires for the same death; that channel is deliberately
            // left unwired here (3.1.2.5 owns adapter exit-signal exposure and relocates this
            // subscriber onto its shared future, the way 3.1.2.6 reads R26's game signal).
            iceAdapter
                    .onExit()
                    .thenAccept(exitCode -> machine.receiveEvent(new AdapterExited(exitCode)));
            iceConnection.connect().get();
            iceConnection
                    .call(
                            "setLobbyInitMode",
                            gameConfig.gameType().equals("matchmaker") ? "auto" : "normal")
                    .get();
            // Empty list of ICE servers, so only public STUN servers will be used.
            Object[] servers = {new Object[0]};
            iceConnection.call("setIceServers", servers).get();
            iceConnection.registerNotification(
                    "onGpgNetMessageReceived",
                    node -> {
                        JsonNode params = node.get("params");
                        if (params == null
                                || !params.isArray()
                                || params.size() < 2
                                || !params.get(1).isArray()) {
                            LOG.warn("Ignoring malformed onGpgNetMessageReceived message");
                        } else if ("GameState".equals(params.get(0).asText())
                                && "Launching".equals(params.get(1).path(0).asText())) {
                            machine.receiveEvent(new StartMatch());
                        }
                    });
            // #192: second, filtering consumer on the same fan-out — watches for the game's own
            // clean-end signal and records it. Sends nothing and tears down nothing directly; R72's
            // frame forwarding (above) is the reporting, R59b's TERMINATED action is the teardown.
            iceConnection.registerNotification("onGpgNetMessageReceived", this::onGpgNetMessage);
            SubprocessManager gameBinary = gameLauncher.start();
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
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        } catch (MockGameLaunchException e) {
            LOG.warn("Could not launch game binary ({})", e.getMessage());
            throw new FailedTransitionException(e.getMessage(), states.get(ClientState.TERMINATED));
        }
    }

    /**
     * PLAYING stay-in-state action for {@link Disconnected} (#193): logs the close reason and does
     * nothing else. See the transition registration in {@link #setupStateMachine()} for the
     * rationale — the harness plays on without reconnect (R40 deferred) instead of tearing down,
     * because peer connections are already established and the lobby is only the signalling relay.
     *
     * @param message the {@link Disconnected} event; guaranteed by registration, never anything
     *     else.
     */
    private void logLobbyLossDuringPlaying(Event message) {
        Disconnected disconnected = (Disconnected) message;
        LOG.warn(
                "Lost lobby connection while PLAYING ({}); continuing without reconnect (R40"
                        + " deferred), session will end via its own game exit",
                disconnected.event());
    }

    /**
     * TERMINATED no-op action for {@link Disconnected} (#193): teardown closes the lobby, which
     * fires this same event on a session that is already torn down. Logged at debug level only —
     * deliberately quieter than the framework's default unregistered-event WARN — and does not
     * re-run teardown.
     *
     * @param message the {@link Disconnected} event; guaranteed by registration, never anything
     *     else.
     */
    private void logSelfInflictedDisconnect(Event message) {
        Disconnected disconnected = (Disconnected) message;
        LOG.debug("Disconnected from lobby after session teardown ({})", disconnected.event());
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
