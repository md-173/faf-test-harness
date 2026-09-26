package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.config.ExitCodes;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
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

    /**
     * A mapping of result strings to numerical scores, as FA itself scores them: {@code
     * VictoryForArmy}, {@code DefeatForArmy} and {@code DrawForArmy} in FA's {@code
     * AbstractVictoryCondition.lua} send {@code "victory 10"}, {@code "defeat -10"} and {@code
     * "draw 0"}.
     *
     * <p>The values are not decoration. faf-server decides a 1v1 matchmaker game by comparing the
     * scores reported for its two armies rather than their outcomes ({@code
     * LadderGame._outcome_override_hook}, on by default), so victory has to outscore defeat for the
     * fixed result described below to be the one it records.
     *
     * <p>{@code draw} is carried for completeness of the mapping and is not reachable: the
     * end-of-match result is fixed: army 1's team wins and the other team loses (WBS-3.2.4.3-fix,
     * #281; teams since WBS-4.3.3). See {@code gameEnds} for why that is the design rather than a
     * gap.
     */
    private static final Map<String, Integer> SCORES =
            Map.of("victory", 10, "defeat", -10, "draw", 0);

    /**
     * The numerical values for the teams in the match. We are not using team 1 since that is the
     * special free-for-all value.
     */
    private static final int[] TEAMS = {2, 3};

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
    private final ScheduledThreadPoolExecutor scheduler;

    /** The delay before initiating a match after all configuration is done. */
    private final Duration launchDelay;

    /** The total duration of the match, after which it is ended. */
    private final Duration matchDuration;

    /**
     * How long after the game enters a session it halts the JVM, or {@code null} to never crash
     * (WBS-5.2). Derived once from {@link MockGameConfig#crashDelay()}.
     */
    private final Duration crashDelay;

    /**
     * What an injected crash calls to end the process. {@code Runtime.getRuntime()::halt} in
     * production; a recorder in the crash-injection tests. See the constructor that takes it.
     */
    private final IntConsumer halt;

    /**
     * Guards {@link #armCrash()} so the crash timer is scheduled exactly once (WBS-5.2).
     *
     * <p>Needed because the two arming points are not mutually exclusive and neither is
     * single-shot: a host registers a peer for every {@code ConnectToPeer} it receives, and can
     * then also enter LIVE. Without this a four-player host would arm four separate crash timers,
     * only the first of which would matter, and the surplus would fire into a dead JVM.
     */
    private final AtomicBoolean crashArmed = new AtomicBoolean(false);

    /**
     * A future that upon completion, drives the state machine to launch the match. Created by the
     * {@code scheduler}. Marked volatile as the state machine thread writes to them and the
     * caller's thread reads from them.
     */
    private volatile ScheduledFuture<?> launchFuture;

    /**
     * A future that upon completion, drives the state machine to end the match. Created by the
     * {@code scheduler}. Marked volatile as the state machine thread writes to them and the
     * caller's thread reads from them.
     */
    private volatile ScheduledFuture<?> matchEndFuture;

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
        FAILED,
        /**
         * Waited in LOBBY for {@code --lobby-timeout-seconds} and nothing ever drove the game into
         * a role, so it gave up (WBS-3.2.1.3, #323). Not a failure — the game booted, connected and
         * announced its lobby; it was simply never used. Only reachable when that flag is set.
         */
        LOBBY_TIMEOUT
    }

    /**
     * A mapping from the {@link GameState} enum to the actual state objects used by {@code
     * machine}.
     */
    private final Map<GameState, State> states;

    /** Guards against {@link #start()} being called more than once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

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
        this(
                config,
                gpgnetServer,
                gpgnetConnectionTimeout,
                launchDelay,
                matchDuration,
                Runtime.getRuntime()::halt);
    }

    /**
     * Internal constructor that also takes the halt action, for the crash-injection tests
     * (WBS-5.2).
     *
     * <p>The halt is a parameter for one reason: {@link Runtime#halt(int)} ends the JVM, so a test
     * that reached the real one would kill the Gradle test worker. That surfaces as a process
     * vanishing with no report rather than as a failing test, which is far worse to diagnose than
     * an ordinary assertion failure. A recording stand-in lets the scheduling, the arming rule and
     * the exit code all be asserted in-process.
     *
     * <p>An {@link IntConsumer} rather than a {@link Runnable} because the exit code is half of
     * what is under test; a seam that dropped it would not cover {@link ExitCodes#INJECTED_CRASH}.
     *
     * @param config the configuration options given to the mock game.
     * @param gpgnetServer a not-yet-connected connection to the GpgNet Server.
     * @param gpgnetConnectionTimeout the timeout to wait on a GpgNet connection for.
     * @param launchDelay the delay before initiating a match after all configuration is done, or
     *     {@code null} if it will be driven entirely manually.
     * @param matchDuration the total duration of the match, after which it is ended, or {@code
     *     null} if it will be driven entirely manually.
     * @param halt what an injected crash calls to end the process; {@code
     *     Runtime.getRuntime()::halt} in production.
     */
    MockGameLifecycle(
            MockGameConfig config,
            GpgNetConnection gpgnetServer,
            Duration gpgnetConnectionTimeout,
            Duration launchDelay,
            Duration matchDuration,
            IntConsumer halt) {
        this.halt = halt;
        this.crashDelay = config.crashDelay().orElse(null);
        this.config = config;
        this.gpgnet = gpgnetServer;
        this.gpgnetConnectionTimeout = gpgnetConnectionTimeout;
        // Only one delay should be scheduled at a time, so one thread is enough.
        // Uses a daemon thread, so that it doesn't keep the JVM up after the the main thread(s)
        // finish executing.
        this.scheduler =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            Thread t = new Thread(r, "game-scheduler");
                            t.setDaemon(true);
                            return t;
                        });
        // stopSchedules() shuts this down, which then discards every task still waiting.
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
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
        this.shutdown = new GameShutdown(machine, gpgnet, this);

        // Assigned here rather than at the field, which would read a still-null config. Registered
        // with the shutdown sequence immediately: that sequence is the only thing that stops the
        // cadence and closes the socket, on every exit path including a SIGTERM before CreateLobby.
        this.traffic = new GameTrafficSession(config.playerId(), config.udpDropPercent());
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
     * {@code SIGTERM} converge on it with no double-teardown. It is safe to run at any phase, the
     * pre-{@link #start()} one included — but there it is <em>terminal</em> for the object rather
     * than merely early: it cancels the FSM's scheduling and burns the sequence's one-shot guard,
     * so a {@code start()} afterwards arms no timeout and the lifecycle can never reach ENDED. The
     * one production path that reaches it, the bootstrap's belt-and-braces teardown, is already on
     * its way out of the JVM.
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
     * Cancels any not-yet-started configured schedules (launch delay, match duration and injected
     * crash) and shuts the scheduler down. Only called by {@link GameShutdown#run()} hence
     * package-private.
     *
     * <p><b>Never interrupts</b> (WBS-3.2.4.1-fix). It uses {@code shutdown()}, not {@code
     * shutdownNow()}, because it often runs on the scheduler's own thread: a match that ends on its
     * own posts {@code GameEnded} from its match-end task, and ENDED's entry hook runs the whole
     * teardown there. An interrupt made the traffic step's wait for its receiver return at once, so
     * the receiver's totals line raced the process exit. A task already due when this runs is still
     * started, and does nothing; see {@code schedule(Runnable, Duration)}.
     */
    /* package-private */ void stopSchedules() {
        scheduler.shutdown();
    }

    /**
     * How many tasks the lifecycle's scheduler holds that have not started yet: a pending launch,
     * match end or injected crash. Package-private for {@code GameShutdownTest}, which checks that
     * a launch was pending before teardown and that teardown discarded it, rather than sleeping
     * past the launch delay. The queue is what tells the two apart: shutting down discards a task
     * that has not started, so it never runs, while without the discard policy set in the
     * constructor a delayed one would stay queued to run when its delay expires.
     *
     * @return the number of tasks still queued on the lifecycle's scheduler.
     */
    /* package-private */ int queuedSchedules() {
        return scheduler.getQueue().size();
    }

    /**
     * Gives a future that completes the next time the lifecycle enters {@code state}, or at once if
     * it is the current state. The wait is edge triggered, as {@link
     * StateMachine#stateReached(State)} documents: a state already entered and left is seen only on
     * a later entry, so a caller driving the game through several states must take every future it
     * needs before the frame that starts the run (WBS-2.3.7-fix, #250).
     *
     * <p>Guarded against a pre-{@link #start()} call, matching {@link #getExitStatus()}. Nothing
     * moves the FSM until {@code start()} opens the connection and arms the timeout, so waiting on
     * a state before then is an unbounded wait with nothing to end it — {@code Main} joins on
     * exactly this future. Failing loudly at the call is better than hanging at the join.
     *
     * @param state the state to wait for.
     * @return a future that completes on the next entry to {@code state}, already complete if it is
     *     the current state.
     * @throws IllegalStateException if called before {@link #start()}.
     */
    public CompletableFuture<Void> stateReached(GameState state) {
        if (!started.get()) {
            throw new IllegalStateException("Tried to await " + state + " before start()");
        }
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
            // WBS-4.3.4: the action is what makes this exit report success. Registered without one
            // the transition left `status` on its initial FAILED, so an orderly peer departure
            // exited 70 and the surviving client classified it as a crash.
            states.get(s)
                    .registerTransition(
                            PeerDisconnected.class,
                            states.get(GameState.ENDED),
                            this::peerDisconnected,
                            null);
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
        gpgnetDispatcher.registerHandler(
                "DisconnectFromPeer", frame -> machine.receiveEvent(new PeerDisconnected(frame)));

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

        // Stays here rather than moving into start() (#312 split the connect out): this only
        // registers a callback on stateReached(LOBBY), so it is wiring, not I/O, and it arms
        // correctly whenever LOBBY is committed regardless of when start() runs.
        armLobbyTimeout();
    }

    /**
     * Opens the GPGNet connection and arms the timeout that ends the game if it never completes.
     * Until this is called the lifecycle is fully wired but inert, and sits in INITIALIZING.
     *
     * <p>Split out of the constructor by WBS-3.2.4.1-fix (#262). Connecting from a constructor gave
     * construction a side effect on a timeline the caller could not control: every test that built
     * a lifecycle in a fixture started a ~500ms clock the moment setup returned, and then raced it
     * from the test body. That is what made {@code LifecycleSetupTest}'s initial-state assertion
     * flake on two unrelated branches, and it would have made the next test written in that shape
     * flake identically. An explicit start also lets a caller register its teardown before anything
     * is open: {@code Main} installs the JVM shutdown hook between construction and this call,
     * closing the window WBS-3.2.5.1 had to accept because the socket opened during construction.
     *
     * @throws IllegalStateException if called more than once
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("start() may only be called once");
        }
        machine.setTimeout(
                gpgnetConnectionTimeout.toMillis(),
                states.get(GameState.ENDED),
                ignored -> {
                    status = ExitStatus.SERVER_NOT_CONNECTED;
                });
        gpgnet.connect()
                .thenRun(() -> machine.receiveEvent(new ServerConnected()))
                .whenComplete(
                        (ignored, error) -> {
                            // Without this the derived future is discarded, so a throw out of the
                            // INITIALIZING -> IDLE chain is captured into it and never observed:
                            // the reader thread carries on into readLoop as though the handshake
                            // succeeded, the FSM stays in INITIALIZING, and the timeout armed above
                            // eventually reports SERVER_NOT_CONNECTED — the opposite of what
                            // happened, since the adapter was reached and the socket is open.
                            // DEBUG because a plain connect failure lands here too and is already
                            // reported by onDisconnect and that timeout.
                            if (error != null) {
                                LOG.debug("GPGNet connect or post-connect handshake failed", error);
                            }
                        });
    }

    /**
     * Arms the optional LOBBY give-up timer, if one is configured (WBS-3.2.1.3, #323).
     *
     * <p>Unset by default, and an unset timer arms nothing at all — a mock game that nothing drives
     * into a role sits in the lobby exactly as a real game does, which is what a consumer asserting
     * on the GPGNet handshake wants. What the flag buys is who ends such a run: the consumer's own
     * {@code timeout} wrapper reports {@code 143} or {@code 130}, so a run that did precisely what
     * was asked is indistinguishable from one killed for hanging. Given the timer, the game exits
     * through the normal path with {@link ExitStatus#LOBBY_TIMEOUT}.
     *
     * <p>Cancellation is the state machine's, not ours: on every state change, {@code
     * commitTransition} disarms each timeout already pending when that change began, so a game
     * driven into HOSTING or JOINING, or dropped into ENDED by a disconnect, never trips this. That
     * is the whole reason it uses {@code setTimeout} rather than the lifecycle's own scheduler,
     * which would need cancelling at each of the four ways out of LOBBY.
     *
     * <p>It is armed from a {@link StateMachine#stateReached} callback, which runs inside the
     * commit into LOBBY. Until WBS-2.3.7-fix (#259) that was load-bearing: LOBBY's entry hook and
     * the transition action both run before {@code commitTransition}, which then discarded every
     * pending timeout, including one they had just armed. The commit now keeps a timeout armed
     * during its own transition, so LOBBY's entry hook would serve as well.
     */
    private void armLobbyTimeout() {
        Optional<Duration> lobbyTimeout = config.lobbyTimeout();
        if (lobbyTimeout.isEmpty()) {
            return;
        }
        machine.stateReached(states.get(GameState.LOBBY))
                .thenRun(
                        () ->
                                machine.setTimeout(
                                        lobbyTimeout.get().toMillis(),
                                        states.get(GameState.ENDED),
                                        ignored -> {
                                            LOG.info(
                                                    "no HostGame or JoinGame within {}s; giving up"
                                                            + " on the lobby",
                                                    lobbyTimeout.get().toSeconds());
                                            status = ExitStatus.LOBBY_TIMEOUT;
                                        }))
                // Observed, not discarded, for the same reason start()'s chain carries one: a throw
                // in the arming lambda would otherwise be captured into a future nobody holds, and
                // the timer would silently never arm. Nothing in the lambda throws today. A
                // CreateLobby that commits IDLE -> LOBBY after GameShutdown.run() has cancelled the
                // FSM's scheduling reaches setTimeout, which then arms nothing and returns rather
                // than throwing (#312, #328).
                .whenComplete(
                        (ignored, error) -> {
                            if (error != null) {
                                LOG.debug(
                                        "lobby give-up timer was not armed: {}", error.toString());
                            }
                        });
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

        // The frame's port decides where the socket binds, and the launch argument is only the
        // fallback (WBS-4.3.2). Source-verified in java-ice-adapter 3.3.14: GPGNetServer fills this
        // frame from GPGNetServer.getLobbyPort(), and Peer.onIceDataReceived relays every inbound
        // peer datagram to 127.0.0.1 on that same accessor — so the port named here is where
        // traffic physically arrives, whatever the launch argument said. 3.2.4.1 called the
        // argument authoritative when nothing bound a socket and the value only decided what to
        // log; binding the other one now would leave the game healthy, quiet, and deaf.
        int lobbyPort = config.lobbyPort();
        try {
            int announced = frame.intArg(1);
            if (announced != config.lobbyPort()) {
                LOG.warn(
                        "CreateLobby names lobby port {} but --lobby-port is {}; binding {} "
                                + "because that is where the adapter sends peer traffic",
                        announced,
                        config.lobbyPort(),
                        announced);
            }
            lobbyPort = announced;
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.warn(
                    "CreateLobby frame did not have a usable lobby port argument; "
                            + "falling back to --lobby-port {}",
                    config.lobbyPort());
        }

        // Bound before the frame below, not after: the lobby server marks the game hosted on
        // GameState Lobby, and from that moment a peer's datagrams can arrive at this port.
        traffic.bind(lobbyPort);

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
            sendPlayerOptions(config.playerId());

            // No game options are required, but any could be passed to test different properties.
            for (var entry : config.gameOptions().entrySet()) {
                gpgnetSender.gameOption(entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            throw recordSendFailure(e);
        }

        // Set up the scheduler if configured. Keeping the handle is what lets a manual
        // launchMatch() cancel the pending one; discarding it left an orphaned LaunchMatch to fire
        // in a state with no matching transition and log "No matching transitions for LaunchMatch".
        if (launchDelay != null) {
            launchFuture = schedule(() -> machine.receiveEvent(new LaunchMatch()), launchDelay);
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

        // Set up the scheduler if configured. Keeping the handle is what lets a manual
        // launchMatch() cancel the pending one; discarding it left an orphaned LaunchMatch to fire
        // in a state with no matching transition and log "No matching transitions for LaunchMatch".
        if (launchDelay != null) {
            launchFuture = schedule(() -> machine.receiveEvent(new LaunchMatch()), launchDelay);
        }

        // A peer is connected and traffic is flowing, so there is now a session to lose (WBS-5.2).
        // Armed after the launch timer, not before it (#357 review): armCrash reads that timer to
        // tell whether the match will end before the crash fires.
        armCrash();
    }

    /* Transition action for HOSTING/JOINING -> LIVE. */
    private void matchBegins(Event event) throws FailedTransitionException {
        try {
            gpgnetSender.gameState("Launching");
        } catch (IOException e) {
            throw recordSendFailure(e);
        }

        // Set up the scheduler if configured. Kept for the same reason as launchFuture above: a
        // manual endMatch() cancels this one rather than leaving it to fire in ENDED.
        if (matchDuration != null) {
            matchEndFuture = schedule(() -> machine.receiveEvent(new GameEnded()), matchDuration);
        }

        // The other arming point (WBS-5.2), for a single game with no peers: it reaches LIVE
        // without anyone ever registering. Scheduled after the match-end timer above so that at
        // equal delays on this one-thread scheduler the match ends first and the crash is
        // cancelled with the rest of the schedule, rather than racing it.
        armCrash();
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
                sendPlayerOptions(peer.playerId());
            } catch (IOException e) {
                throw recordSendFailure(e);
            }
        }

        // As in joinGame: the first peer to arrive starts the crash timer (WBS-5.2). Later peers
        // are absorbed by armCrash's own guard rather than tested for here.
        //
        // Armed after this action's own I/O, not before it (#357 review). With
        // --crash-after-seconds=0 the scheduler can halt the JVM the instant the timer is armed, so
        // arming first left a host able to die midway through the peer's PlayerOption frames, with
        // the peer registered for traffic but never configured and a half-written frame on the
        // socket. Arming last gives a zero-delay crash a defined landing point, and matches
        // joinGame, which already armed after its own work. A send failure above now throws past
        // this line rather than through it, which is the right way round: that throw goes to ENDED,
        // whose shutdown cancels the schedule anyway.
        armCrash();
    }

    /**
     * Transition action for a peer departure, registered from every non-ENDED state into ENDED
     * (WBS-4.3.4).
     *
     * <p>Reached from the {@code DisconnectFromPeer} GPGNet handler, which the adapter emits only
     * because this side's mock client relayed faf-server's departure notice. That notice is sent
     * only while the server's game is in its LOBBY phase ({@code GameConnection.abort} guards
     * {@code disconnect_all_peers} on it), so after launch this action is unreachable and the
     * survivor learns of a departure from its own adapter instead. See the runbook's multi-peer
     * limitations for that second path.
     *
     * <p><b>Why this sets OK.</b> The transitions for this event predate any handler and carried no
     * action, so {@code status} kept its initial {@link ExitStatus#FAILED} and the process exited
     * {@link com.faforever.testharness.game.config.ExitCodes#RUNTIME}. A departure is a modelled,
     * orderly end rather than a fault, and reporting it as one made the surviving client's {@code
     * classifyGameExit} log "exited abnormally" for a session that did exactly what it was told.
     *
     * <p><b>Why a malformed frame does not.</b> A frame we could not read keeps FAILED and throws,
     * matching {@link #joinGame} and {@link #peerConnectionRequest} and the convention {@link
     * #recordSendFailure} states outright: a malformed inbound frame is a real generic failure. The
     * throw targets ENDED, which is where this transition was going anyway, so it changes the exit
     * status and nothing else.
     *
     * <p><b>Scope, and what WBS-4.3.3 inherits.</b> Any single peer loss ends the game, because the
     * transitions are registered per state rather than per remaining peer. Correct at two players
     * and wrong above them. The departing id is read and logged here so that the decision to play
     * on until the last peer leaves can start from an event that already names who left. It is only
     * a starting point, not the whole job: {@code peers} is never pruned and {@link
     * GameTrafficSession} has no counterpart to {@code registerPeer}, so playing on means teaching
     * both of those about departure as well.
     *
     * <p>Runs on the GPGNet reader thread, as every inbound handler does. ENDED's entry hook closes
     * that same socket, which is safe because {@link GpgNetConnection#close()} does not join the
     * reader, and the resulting local close is filtered before it reaches the FSM.
     *
     * @param event the {@link PeerDisconnected} event; guaranteed by registration.
     * @throws FailedTransitionException if the frame carries no usable player id.
     */
    private void peerDisconnected(Event event) throws FailedTransitionException {
        if (!(event instanceof PeerDisconnected)) {
            throw new AssertionError(
                    "peerDisconnected called without a PeerDisconnected event, "
                            + "should be impossible");
        }
        GpgNetFrame frame = ((PeerDisconnected) event).frame();
        int playerId;
        try {
            playerId = frame.intArg(0);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            LOG.error("DisconnectFromPeer frame did not have a player id argument");
            throw new FailedTransitionException(e.getMessage(), states.get(GameState.ENDED));
        }

        LOG.info("Peer (ID: {}) disconnected, ending game", playerId);
        status = ExitStatus.OK;
    }

    /* Transition action for LIVE -> ENDED. */
    private void gameEnds(Event event) throws FailedTransitionException {
        try {
            // Fixed by design, not pending configuration (WBS-3.2.4.3-fix, #281). Army 1's team
            // wins and the other team loses, on every run: the harness asserts on the shape and
            // ordering of the closing frames, and a result that varied would make those assertions
            // depend on configuration that no consumer has asked to vary. A mock whose output is
            // the same every time is the point of it. If a card ever needs a specific outcome, the
            // values belong on MockGameConfig alongside gameOptions rather than here.
            //
            // GameResult is keyed by army, not team (faf-server handle_game_result(army, result)),
            // so every army reports its team's result (WBS-4.3.3). Army 1 is always on TEAMS[0].
            // Before teams existed this rule read "army 1 wins, every other army loses"; with two
            // teams that would have army 3 report defeat while its team wins.
            //
            // The host and every joiner send this same set, which is how FA itself reports: its
            // sim declares a result for every army and UserSync.lua sends each one from every
            // client. faf-server keeps each player's report per army and resolves each army across
            // them, unanimously when they agree (GameResultReports._compute_outcome). Reporting
            // from each game's own vantage, the alternative #384 raised, means nothing to
            // faf-server, which reads every army id in the host's numbering (below) whoever sent
            // it. A game whose set disagreed would split the vote for an army, which faf-server
            // settles by majority where it can and otherwise marks CONFLICTING. That can leave the
            // game unresolved, or draw a 1v1 matchmaker game, which is decided by score.
            //
            // The army ids are the host's: faf-server takes PlayerOption from no other game, and
            // add_result drops any army the host did not give to a player present at launch. A
            // joiner never learns its own army and does not need to. Every game runs this loop,
            // and while nobody leaves every game holds the same number of peers, so the games
            // always agree with each other. The risk is that together they stop matching the
            // host's frames, if this loop and sendPlayerOptions (arrival order numbering, teams by
            // teamForArmy) drift apart: nothing here fails, and faf-server can resolve the game
            // wrongly or not at all. PeerResultAgreementTest derives its expectation from the
            // host's frames to catch exactly that.
            //
            // The agreement between games depends on nobody leaving. peers is never pruned, so a
            // departed player's army stays in the range of every game that knew them, but a player
            // joining afterwards never hears of them and its range falls short. Playing on after a
            // departure needs more than pruning peers, since the host does not renumber.
            for (int army = 1; army <= peers.size() + 1; army++) {
                String result = teamForArmy(army) == TEAMS[0] ? "victory" : "defeat";
                gpgnetSender.gameResult(army, result, SCORES.get(result));
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

    /**
     * Arms the injected crash (WBS-5.2), if one is configured and none is armed yet.
     *
     * <p><b>Why the timer starts here and not on entry to LIVE.</b> The card that asked for this
     * said "measured from entry to LIVE, so the crash lands mid-match while peers are connected".
     * In this harness those two halves contradict each other. LIVE is reachable only through {@link
     * #launchFuture}, which {@link #beginHosting} and {@link #joinGame} arm only when {@code
     * launchDelay} is non-null, and a multi-peer session must disable auto-launch, because
     * faf-server refuses a {@code game_join} once the host reports {@code GameState Launching} (see
     * {@link MockGameConfig#launchDelay()}). So a multi-peer game never enters LIVE at all, and a
     * LIVE-anchored crash would have been silently inert in the one configuration the fault is most
     * worth injecting into.
     *
     * <p>Peers connect and exchange traffic in HOSTING and JOINING, not in LIVE. So the condition
     * the card was reaching for, the game having a session to lose, is "a peer is registered, or
     * the match went live", whichever happens first. That is reachable in both configurations, and
     * it keeps the flag's meaning stable: {@code N} is always N seconds after this game first had
     * something to lose, rather than N seconds after a milestone that may never arrive.
     *
     * <p>Anchoring on entry to LOBBY instead would have been simpler, and was rejected: the timer
     * would start before any peer connected, so the same command would crash a game with peers or
     * without them depending on how quickly the lobby paired players. Non-determinism is the one
     * property a fault-injection knob cannot afford.
     */
    private void armCrash() {
        if (crashDelay == null || !crashArmed.compareAndSet(false, true)) {
            return;
        }
        // Logged only once the task is actually queued. The runbook makes this line the operator's
        // proof that the timer started, and schedule() returns null rather than throwing when the
        // scheduler is already shut down, so announcing first would let a torn-down lifecycle claim
        // an armed crash it never armed.
        if (schedule(this::injectCrash, crashDelay) == null) {
            return;
        }
        LOG.info(
                "injected crash armed: halting with exit code {} in {}s",
                ExitCodes.INJECTED_CRASH,
                crashDelay.toSeconds());
        Duration untilMatchEnds = timeUntilMatchEnds();
        if (untilMatchEnds != null && crashDelay.compareTo(untilMatchEnds) >= 0) {
            LOG.warn(
                    "injected crash is due in {}s but the match is due to end in {}ms; a match that"
                            + " ends first cancels the crash, so this run will likely inject no"
                            + " fault",
                    crashDelay.toSeconds(),
                    untilMatchEnds.toMillis());
        }
    }

    /**
     * How long until the pending match-end timer fires, as far as the schedule already says
     * (WBS-5.2), or {@code null} when nothing is scheduled to end the match.
     *
     * <p>Read by {@link #armCrash()} to warn about a crash that cannot land. Both timers share the
     * one scheduler, and the ENDED entry hook shuts that scheduler down, so a crash still queued
     * when the match ends is cancelled and the run exits {@code 0} with nothing in the log to say
     * why.
     *
     * <p>Answered here rather than at startup (#357 review), because only here is the crash's own
     * start known. A crash armed on entry to LIVE races the match-end timer {@code matchBegins} has
     * just scheduled. One armed by a peer connecting starts earlier, before the launch timer has
     * fired, so its match ends a launch delay plus a match duration later. A startup check could
     * only compare the crash delay with the match duration, which warned a joiner that no fault
     * would be injected when its crash was due well inside the match.
     *
     * <p>{@code null} when auto-launch is off: nothing posts {@code LaunchMatch}, so no match-end
     * timer is ever created and nothing can cancel the crash. That is the configuration a
     * multi-peer session runs in.
     *
     * @return the time until the match is due to end, or {@code null} if it is not scheduled to
     */
    private Duration timeUntilMatchEnds() {
        ScheduledFuture<?> matchEnd = matchEndFuture;
        if (matchEnd != null && !matchEnd.isDone()) {
            return Duration.ofMillis(matchEnd.getDelay(TimeUnit.MILLISECONDS));
        }
        ScheduledFuture<?> launch = launchFuture;
        if (launch != null && !launch.isDone() && matchDuration != null) {
            return Duration.ofMillis(launch.getDelay(TimeUnit.MILLISECONDS)).plus(matchDuration);
        }
        return null;
    }

    /**
     * The injected crash itself (WBS-5.2): ends the process where it stands.
     *
     * <p>{@link Runtime#halt(int)} and never {@link System#exit(int)}. Exit runs the JVM shutdown
     * hooks, and {@code Main} registers one that runs {@link GameShutdown}: stopping scheduling,
     * then closing the GPGNet socket and stopping the traffic session, in that order. A consumer
     * watching the adapter would see a tidy disconnect, which is the opposite of the fault being
     * injected. Halt runs no hook, writes no closing frame, and leaves the socket to be torn down
     * by the operating system exactly as it would be if the process had been killed.
     *
     * <p>The log line precedes the halt and does reach disk: the appenders in {@code logback.xml}
     * are a {@code ConsoleAppender} and a {@code RollingFileAppender} with no {@code AsyncAppender}
     * in front of either, so both flush on write. It is the only warning an operator gets that the
     * silence that follows was deliberate.
     */
    private void injectCrash() {
        LOG.warn(
                "injected crash firing: halting the JVM with exit code {} and no shutdown "
                        + "(--crash-after-seconds)",
                ExitCodes.INJECTED_CRASH);
        halt.accept(ExitCodes.INJECTED_CRASH);
    }

    /* Wrapper around ScheduledExecutorService.schedule that catches RejectedExecutionExceptions
     * and logs them. */
    private ScheduledFuture<?> schedule(Runnable command, Duration delay) {
        try {
            return scheduler.schedule(
                    () -> {
                        // stopSchedules() discards every task still waiting, but one already due
                        // is still started, and returns here (WBS-3.2.4.1-fix). At equal delays
                        // this is what cancels a crash with the match end that tore the game down.
                        if (!scheduler.isShutdown()) {
                            command.run();
                        }
                    },
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The scheduler has likely been shut down, so we log and return null.
            LOG.debug(
                    "Could not schedule mock game lifecycle task, "
                            + "likely due to a shut down scheduler");
            return null;
        }
    }

    /* Sends the set of PlayerOption values needed for a player in the match. */
    private void sendPlayerOptions(int playerId) throws IOException {
        // Players assigned army number (and start spot, faction, and color) in arrival order, with
        // the host being first.
        int army = peers.size() + 1;
        gpgnetSender.playerOption(playerId, "Army", army);
        gpgnetSender.playerOption(playerId, "Team", teamForArmy(army));
        gpgnetSender.playerOption(playerId, "StartSpot", army);
        gpgnetSender.playerOption(playerId, "Faction", army);
        gpgnetSender.playerOption(playerId, "Color", army);
    }

    /* The team an army plays on. Configured for a two-team game, so armies alternate between
     * TEAMS[0] and TEAMS[1]; more than two teams would make faf-server mark the game MULTI_TEAM. */
    private static int teamForArmy(int army) {
        return TEAMS[(army - 1) % 2];
    }
}
