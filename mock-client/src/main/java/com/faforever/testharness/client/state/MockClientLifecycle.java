package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.GameHostConfig;
import com.faforever.testharness.client.config.GameJoinConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.GpgNetForwarder;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.ice.IceEventLogger;
import com.faforever.testharness.client.ice.IceSignalRelay;
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
import com.faforever.testharness.client.process.LaunchIdentity;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    /**
     * The session's single adapter-exit signal (WBS-3.1.2.5): completes with the ICE adapter
     * process's exit code once the process launched by {@link #launchGame} exits. Never completes
     * if no adapter launches. The R26 pattern applied to the adapter — single ownership, one
     * exposed future with copy semantics — mirrors {@link #gameExit} exactly; see that field for
     * the shared rationale.
     */
    private final CompletableFuture<Integer> adapterExit = new CompletableFuture<>();

    /**
     * The session's single game-launch signal (#218): completes with the {@code game_launch} config
     * once {@link #launchGame} has brought the adapter and the game up. Never completes if no game
     * launches. Mirrors {@link #gameExit}, and is the only in-process route to this session's game
     * uid — the value a second client needs as its join target.
     */
    private final CompletableFuture<GameConfig> gameLaunched = new CompletableFuture<>();

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
     * The lobby-assigned identity for this session (WBS-3.1.2.9), captured from the {@code welcome}
     * on the CONNECTING to IDLE edge. Non-null from IDLE onwards, because that is the only edge
     * into IDLE and {@link WelcomeReceived} rejects a null state.
     */
    private SessionState sessionIdentity;

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

        // The framework assigns the initial state directly and never runs its entry hooks, so the
        // hook registered in setupStateMachine cannot report CONNECTING. Emit it here instead,
        // otherwise a harness reading the log would never see the state the client starts in
        // (WBS-3.1.6.2).
        logStateEntry(ClientState.CONNECTING);
    }

    private void setupStateMachine() {
        // Harness-facing state reporting (WBS-3.1.6.2). Registered before every other entry hook
        // so the line precedes that state's side effects, such as the game_host send on IDLE and
        // teardown on TERMINATED. Self-loops skip entry hooks, so a stay-in-state transition such
        // as the lobby-loss path on PLAYING does not emit a duplicate line.
        for (var s : ClientState.values()) {
            states.get(s).onEntry(() -> logStateEntry(s));
        }

        // Transitions between states, caused by internal events.
        states.get(ClientState.CONNECTING)
                .registerTransition(
                        WelcomeReceived.class,
                        states.get(ClientState.IDLE),
                        this::onWelcomeReceived,
                        null);
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

        registerConnectToPeerTransitions();
        registerAdapterExitedTransitions();

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

        // #211: a game that dies before reaching PLAYING must still drive the FSM to TERMINATED
        // instead of leaving the client hanging. Same action as the PLAYING edge above — cancelling
        // the (not-yet-armed, in these states) safety-net task is a harmless no-op here.
        states.get(ClientState.STARTING_GAME)
                .registerTransition(
                        GameExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onGameExited,
                        null);
        states.get(ClientState.HOSTING)
                .registerTransition(
                        GameExited.class,
                        states.get(ClientState.TERMINATED),
                        this::onGameExited,
                        null);
        states.get(ClientState.JOINING)
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
        lobby.registerHandler(
                "ConnectToPeer", message -> machine.receiveEvent(new ConnectToPeer(message)));

        // Wire the game exiting to the appropriate event. Async (#211, and also load-bearing for
        // #214): a game that exits near-instantly can complete gameExit on the same thread that
        // is still inside launchGame()/StateMachine#receiveEvent, before the LaunchGame transition
        // has installed STARTING_GAME as the current state — receiveEvent is synchronized and
        // publishes the new state before releasing its lock, so hopping to the common pool here
        // guarantees this continuation cannot run until that transition has actually completed.
        // It also keeps GameExited's TERMINATED entry hook (which synchronously terminates the
        // adapter and awaits its exit) off the JDK's process-reaper machinery, which the adapter's
        // own exit wiring below needs free to observe that death.
        gameExit.thenAcceptAsync(this::onGameProcessExit);
    }

    /**
     * Registers the peer-setup edges (#218): a {@code ConnectToPeer} from the lobby is accepted
     * while hosting and while joining alike.
     *
     * <p>Stay-in-state on both, because the frame changes what the adapter is doing, not what phase
     * this client is in — the host is still HOSTING and a joiner still JOINING once the peer relay
     * exists. Both states rather than just HOSTING because faf-server sends the frame to every
     * player already in the lobby when another arrives: the host on the first join ({@code
     * connect_to_host}), and each peer already present from the third onwards ({@code
     * connect_to_peer}). Self-loops skip entry hooks, so no duplicate state line is emitted per
     * peer. Split out of {@link #setupStateMachine()} to keep that method under the checkstyle
     * length limit.
     */
    private void registerConnectToPeerTransitions() {
        states.get(ClientState.HOSTING)
                .registerTransition(
                        ConnectToPeer.class,
                        states.get(ClientState.HOSTING),
                        this::connectToPeer,
                        null);
        states.get(ClientState.JOINING)
                .registerTransition(
                        ConnectToPeer.class,
                        states.get(ClientState.JOINING),
                        this::connectToPeer,
                        null);
    }

    /**
     * Registers the ICE adapter death edges (#214): no session survives an adapter exit to restart
     * into (verified against downlords-faf-client and java-ice-adapter — see class javadoc for this
     * card), so every post-launch state tears down rather than hanging. Pre-launch failures are
     * already handled by {@link #launchGame}'s own exception handling and never reach this event.
     * Split out of {@link #setupStateMachine()} to keep that method under the checkstyle length
     * limit.
     */
    private void registerAdapterExitedTransitions() {
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

    /**
     * The session's single adapter-exit signal: completes exactly once with the ICE adapter
     * process's exit code, whether it quit cleanly or was killed. Same copy-semantics contract as
     * {@link #gameExit()} — see there for the full details, which apply identically here.
     *
     * @return a future resolving to the adapter's exit code
     */
    public CompletableFuture<Integer> adapterExit() {
        return adapterExit.copy();
    }

    /**
     * The session's single game-launch signal (#218): completes exactly once with the {@code
     * game_launch} config this session launched under, after the ICE adapter and the game are both
     * running. Safe to call at any time — before, during, or after the launch. If no game ever
     * launches, or the launch fails, the future never completes; the FSM reaching TERMINATED is the
     * signal for that case, and a caller waiting here should bound its wait accordingly.
     *
     * <p>Its reason to exist is the game uid, which a second client needs as the target of its
     * {@code game_join} and which reaches no other in-process surface (the harness log line
     * WBS-3.1.6.2 carries it, but parsing logs is not an in-JVM interface).
     *
     * <p>Each call returns an independent copy; cancelling or completing it does not affect other
     * subscribers. Continuations run on whichever thread drove the launch transition — hand
     * non-trivial work to your own executor.
     *
     * @return a future resolving to the config this session's game was launched under
     */
    public CompletableFuture<GameConfig> gameLaunched() {
        return gameLaunched.copy();
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
     * {@link #gameExit} completion handler (#211): classifies the exit locally, then sends the
     * lobby the same generic {@code GameState Ended} frame the real client sends on every
     * termination (clean or crashed; see faf-client's {@code GameRunner.notifyGameEnded}), before
     * posting {@link GameExited} so the frame leaves before teardown closes the connection. Crash
     * detail itself never reaches the server — only this local log line carries it, per the card's
     * source verification (no crash-report command exists in the protocol).
     *
     * <p>Classification: INFO on a zero exit; INFO (not WARN) on a non-zero exit once {@link
     * #teardown}'s {@link SessionTeardown#hasRun()} is {@code true}, since {@link
     * SessionTeardown#run()} sets that flag before it sends the SIGTERM that produces exactly this
     * exit code (143 on POSIX, 1 on Windows) on every ordinary shutdown, not just the #192 safety
     * net — the real client's {@code gameKilled} flag suppresses the same false crash. Otherwise
     * WARN with the code. A genuine crash is unaffected: it completes {@link #gameExit} — and so
     * this handler — before teardown ever runs, so the flag is still false.
     *
     * @param exitCode the game process's exit code.
     */
    private void onGameProcessExit(int exitCode) {
        if (exitCode == 0) {
            LOG.info("mock-game exited cleanly with exit code {}", exitCode);
        } else if (teardown.hasRun()) {
            LOG.info("mock-game exited with code {} after harness-initiated teardown", exitCode);
        } else {
            LOG.warn("mock-game exited abnormally with exit code {}", exitCode);
        }
        sendGameStateEnded();
        machine.receiveEvent(new GameExited(exitCode));
    }

    /**
     * Sends {@code {command: "GameState", target: "game", args: ["Ended"]}} to the lobby — the
     * exact envelope R72's {@link com.faforever.testharness.client.ice.GpgNetForwarder} would send
     * for a {@code GameState Ended} GPGNet frame. Sent directly here rather than through the
     * forwarder because a crashed/killed game process never emits this frame to the adapter itself.
     * Fire-and-forget, matching the forwarder's own send: a failure is logged and otherwise
     * ignored, since a dead lobby connection surfaces through the connection's own disconnect
     * listener.
     */
    private void sendGameStateEnded() {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("command", "GameState");
        envelope.put("target", "game");
        envelope.putArray("args").add("Ended");
        lobby.send(envelope)
                .whenComplete(
                        (ok, error) -> {
                            if (error != null) {
                                LOG.warn(
                                        "failed to send GameState Ended to lobby: {}",
                                        error.getMessage());
                            }
                        });
    }

    /**
     * {@link AdapterExited} transition action for STARTING_GAME/HOSTING/JOINING/PLAYING →
     * TERMINATED (#214): classifies the adapter's own exit the way the real client's {@code
     * IceAdapterImpl} does — INFO on a clean exit(0), WARN with the code otherwise. The exit-code
     * check comes first (WBS-3.1.2.5): {@link SessionTeardown}'s adapter step now quits the adapter
     * before ever signalling it, so a clean teardown produces the adapter's own exit(0) here, and
     * that must still read as the real client's "terminated normally" INFO line, not be downgraded
     * to DEBUG just because teardown happened to be running. Only a non-zero code observed once
     * {@link SessionTeardown#hasRun()} falls back to DEBUG — that is the SIGTERM/ SIGKILL fallback
     * firing because quit didn't land, an expected shutdown code, not a crash. The TERMINATED entry
     * hook (registered in {@link #setupStateMachine()}) runs the actual teardown; this method only
     * logs.
     *
     * @param event the {@link AdapterExited} event that triggered this transition.
     */
    private void onAdapterExited(Event event) {
        int exitCode = ((AdapterExited) event).exitCode();
        if (exitCode == 0) {
            LOG.info("ICE adapter terminated normally");
        } else if (teardown.hasRun()) {
            LOG.debug("ICE adapter exited (code={}) during session teardown", exitCode);
        } else {
            LOG.warn("ICE adapter exited abnormally (code={})", exitCode);
        }
    }

    /**
     * CONNECTING to IDLE transition action (WBS-3.1.2.9). Caches the lobby-assigned identity so
     * {@link #launchGame} can hand it to both subprocesses instead of the config defaults they used
     * before.
     *
     * @param event the {@link WelcomeReceived} event that triggered this transition.
     */
    private void onWelcomeReceived(Event event) {
        // Deliberately not logged here. RunCommand already reports the authenticated identity on
        // reaching IDLE, and each launcher logs the full argv it spawns with.
        sessionIdentity = ((WelcomeReceived) event).state();
    }

    private void launchGame(Event message) throws FailedTransitionException {
        if (!(message instanceof LaunchGame)) {
            throw new AssertionError(
                    "launchGame method called without a LaunchGame event, should be impossible");
        }
        GameConfig gameConfig = ((LaunchGame) message).config();
        // WBS-3.1.2.9: launch under the identity the lobby assigned, not the config defaults. The
        // adapter half is what matters, since faf-ice-adapter copies its --id and --login straight
        // into the CreateLobby frame that tells the game who it is.
        LaunchIdentity identity =
                new LaunchIdentity(sessionIdentity.id(), sessionIdentity.login(), gameConfig.uid());
        try {
            SubprocessManager iceAdapter = iceLauncher.start(identity);
            // Register adapter for teardown.
            teardown.registerAdapterProcess(iceAdapter);
            // WBS-3.1.2.5: single ownership of the adapter process exit in the R26 pattern —
            // exactly one subscriber on the raw process future, completing the shared adapterExit
            // signal. The RPC connection's onDisconnect fires for the same death; that channel is
            // deliberately left unwired, this process exit is the single detection channel.
            iceAdapter.onExit().thenAccept(adapterExit::complete);
            // #214: the FSM's adapter-death subscriber reads the shared signal instead of the
            // process directly, the way 3.1.2.6 reads R26's game signal (gameExit()).
            //
            // Async is load-bearing, not a style choice: Process.onExit()'s dependents run
            // synchronously on the JDK's internal process-reaper machinery by default, and this
            // event's handling can itself block on that same machinery (TERMINATED's entry hook
            // synchronously terminates subprocesses via SessionTeardown, which awaits their exit
            // futures). A synchronous thenAccept here ties up the reaper thread that a concurrent
            // game-exit teardown is waiting on to observe *this* adapter's death, stalling it for
            // a full termination grace. thenAcceptAsync moves the event post off that thread.
            adapterExit()
                    .thenAcceptAsync(exitCode -> machine.receiveEvent(new AdapterExited(exitCode)));
            // Harness-facing connection-state reporting (WBS-3.1.6.2). Read-only observer on the
            // adapter fan-out: it reports the GPGNet link and the per-peer ICE transitions the
            // Phase 5 fault-injection tests measure, and sends nothing. Registered before connect
            // so no notification can arrive before its handlers exist; registration needs the
            // connection to exist, not to be connected.
            new IceEventLogger(iceConnection).start();
            // The two halves of the client's signalling role (#218), started here because this is
            // the first moment both connections exist. Registration is independent of connection
            // state, and doing it before connect() is load-bearing on the same grounds as the
            // logger above: the adapter starts talking the instant the socket opens.
            //
            // R39 relays ICE candidates in both directions, without which no peer link can form at
            // all. R72 forwards the game's GPGNet frames to the lobby, without which the server
            // never learns this game reached Lobby — and until it does, the game is not joinable
            // (faf-server gates game_join on GameState.LOBBY) and no peer is ever announced. Both
            // were built and tested under 3.1.4.5/3.1.4.6 and wired into no session until now.
            new IceSignalRelay(lobby, iceConnection).start();
            new GpgNetForwarder(lobby, iceConnection).start();
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
            SubprocessManager gameBinary = gameLauncher.start(identity);
            // Single ownership of the game process (WBS-3.1.2.4): register it for coordinated
            // teardown and fan its exit code into the session's one exit signal. Consumers
            // subscribe via gameExit(); nothing else touches the manager's onExit.
            teardown.registerGameProcess(gameBinary);
            gameBinary.onExit().thenAccept(gameExit::complete);
            // Harness-facing identity line (WBS-3.1.6.2). The uid is what a second instance needs
            // as its join target, and it reaches no other output. Emitted last, once the adapter
            // and game are actually up, so a harness never receives a join target for a session
            // that failed on the way in; a failed launch reports state entry: TERMINATED instead.
            // Logged here rather than in GameLaunchHandler so it reports the game this client
            // entered, not every game_launch frame that arrived. Host and joiner both receive
            // game_launch, so one line serves both roles. The free-text name is last, keeping the
            // fields ahead of it unambiguous to parse.
            LOG.info(
                    "game launch: uid={} mod={} name={}",
                    gameConfig.uid(),
                    gameConfig.mod(),
                    gameConfig.name());
            // In-process counterpart of the line above (#218), completed at the same point and for
            // the same reason: a second client needs this session's uid as its join target, and an
            // in-JVM harness has nowhere else to read it from. Completed last, so a consumer never
            // receives a join target for a session that died on the way in — a failed launch
            // leaves it pending and the FSM reaches TERMINATED instead.
            gameLaunched.complete(gameConfig);
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
     * HOSTING/JOINING stay-in-state action for {@link ConnectToPeer} (#218): issues the adapter's
     * {@code connectToPeer(remotePlayerLogin, remotePlayerId, offer)} RPC (json-rpc-spec.md §4) for
     * the peer the lobby named, which is what creates the local PeerRelay and starts ICE
     * negotiation with that peer.
     *
     * <p>The {@code offer} flag is read from the frame, never assumed: faf-server sends {@code
     * true} to the side that should be the ICE initiator and {@code false} to the other, and
     * getting it backwards would have both peers offering or both answering. The wire shape is
     * {@code args: [player_name, player_uid, offer]} — verified against faf-server's {@code
     * GpgNetServerProtocol.send_ConnectToPeer}, and matching lobby-protocol-spec.md §10.4.
     *
     * <p>A malformed frame fails the transition into TERMINATED, deliberately the same treatment
     * {@link #hostGame} and {@link #joinGame} give theirs: the peer link cannot be set up from a
     * frame we could not read, and a session that silently carries on without one would report a
     * connection failure that the harness would have to attribute by hand. A frame that parses but
     * whose RPC then fails ends the session too, asynchronously — see the comment on the call.
     *
     * @param message the {@link ConnectToPeer} event; guaranteed by registration.
     * @throws FailedTransitionException if the frame is malformed.
     */
    private void connectToPeer(Event message) throws FailedTransitionException {
        if (!(message instanceof ConnectToPeer)) {
            throw new AssertionError(
                    "connectToPeer method called without a ConnectToPeer event, should be"
                            + " impossible");
        }
        JsonNode command = ((ConnectToPeer) message).command();
        JsonNode remoteLogin = command.path("args").path(0);
        JsonNode remoteId = command.path("args").path(1);
        JsonNode offer = command.path("args").path(2);
        if (!remoteLogin.isTextual() || !remoteId.isInt() || !offer.isBoolean()) {
            throw new FailedTransitionException(
                    "textual remote login, int remote id, and boolean offer arguments not found in"
                            + " ConnectToPeer message",
                    states.get(ClientState.TERMINATED));
        }

        LOG.info(
                "peer connect: login={} id={} offer={}",
                remoteLogin.asText(),
                remoteId.asInt(),
                offer.asBoolean());
        int peerId = remoteId.asInt();
        // Deliberately not awaited, unlike hostGame/joinGame. Those run at role assignment, before
        // any candidate flows; this one runs while ICE signalling is live, and the wait is not
        // free. LobbyConnection requests its next frame only after the handler returns, so a
        // blocked handler stops IceMsg and pong delivery outright, and receiveEvent is
        // synchronized, so it also holds the FSM monitor throughout. Those two combine into a
        // self-inflicted timeout: a GameState Launching arriving on the adapter's reader thread
        // blocks on that monitor, which stops the same reader from delivering this call's
        // response, which fails the call once its timeout expires.
        //
        // A failure still ends the session, just asynchronously — without the relay this peer is
        // unreachable, and a session that carried on would look healthy while silently unable to
        // connect. An adapter that has died outright is not this path's concern: its process exit
        // posts AdapterExited (#214), which is the reliable channel for that.
        iceConnection
                .call("connectToPeer", remoteLogin.asText(), peerId, offer.asBoolean())
                .whenComplete(
                        (result, error) -> {
                            if (error != null) {
                                LOG.warn(
                                        "peer relay setup failed for id={} ({}); ending session",
                                        peerId,
                                        error.getMessage());
                                machine.receiveEvent(new ShutdownRequested());
                            }
                        });
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
     * Emits the harness-facing state line for one state entry (WBS-3.1.6.2). The format is a
     * documented interface, described in {@code mock-client/README.md} § "Harness log contract".
     * Changing it breaks the harness cards that parse it.
     *
     * @param state the state that was just entered.
     */
    private void logStateEntry(ClientState state) {
        LOG.info("state entry: {}", state);
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
