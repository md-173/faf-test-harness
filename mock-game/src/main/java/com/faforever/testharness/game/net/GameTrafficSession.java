package com.faforever.testharness.game.net;

import com.faforever.testharness.game.activity.GameTicker;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mock game's peer traffic, wired to its lifecycle (WBS-4.3.2). Owns the one lobby socket, the
 * sender (WBS-3.2.2.5) and the receiver (WBS-3.2.2.6) that share it, and the progress line the
 * two-peer exchange test asserts on.
 *
 * <p>Three calls, made by {@code MockGameLifecycle} from the frames it already parses:
 *
 * <ul>
 *   <li>{@link #bind(int)} on {@code CreateLobby}, before the game answers {@code GameState Lobby}
 *       — the lobby server marks the game hosted on that frame, after which a peer's datagrams can
 *       arrive and would otherwise hit a closed port.
 *   <li>{@link #registerPeer(String, int)} on {@code JoinGame} and {@code ConnectToPeer}, with the
 *       {@code net_address} and {@code remote_player_id} from the frame. The first one starts the
 *       cadence.
 *   <li>{@link #close()} from the shutdown sequence (WBS-3.2.5.2), which stops the cadence and
 *       closes the socket, ending the receive loop per 3.2.2.6's contract.
 * </ul>
 *
 * <p><b>Why the cadence starts on the first peer rather than on LIVE.</b> mock-game has no external
 * launch trigger — {@code LaunchMatch} is posted only in-process or by the launch-delay timer — and
 * the two-peer live session (WBS-4.3.1) disables auto-launch so the host stays joinable, so traffic
 * gated on LIVE would never flow there. It is also the faithful choice, source-verified in FA:
 * {@code CreateLobby(protocol, localPort, …)} builds the one autolobby communications object on
 * that port, and {@code ConnectToPeer}, {@code SendData} and the pre-launch {@code
 * ShareLaunchStatusThread} all run on that same object — so the real game is already exchanging
 * peer datagrams over its lobby socket before it launches. There is deliberately no {@code
 * stateReached(ENDED)} subscription either — that state's entry hook <em>is</em> the shutdown
 * sequence, and a transition runs the entry hook before the awaited future completes, so such a
 * subscription could never be the thing that stops the cadence.
 *
 * <p><b>Nothing here can kill the game.</b> A lobby port that will not bind, a peer address that
 * will not parse, and a send that fails are all logged and dropped: the game's GPGNet duties are
 * unaffected by having no peer traffic, and a mock that exits over a busy UDP port would take
 * unrelated tests with it. A run that exchanged nothing says so in the log rather than in an exit
 * code.
 *
 * <p>Threading: {@link #registerPeer} arrives on the GPGNet reader thread, {@link #close()} on the
 * FSM thread, that same reader thread (a remote close drives the FSM to ENDED) or the JVM shutdown
 * hook, and the progress tick on its own timer thread. Every method is synchronized on this
 * session; nothing here calls back into the state machine, so no lock order is introduced.
 *
 * <p>One edge is load-bearing rather than incidental: {@link #close()} holds this monitor while it
 * stops the tickers, taking each ticker's monitor, and a ticker thread delivering a progress tick
 * wants this monitor. That is not an inversion only because {@code GameTicker.deliver} is
 * deliberately unsynchronized — synchronizing it would deadlock teardown.
 */
public final class GameTrafficSession implements AutoCloseable {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameTrafficSession.class);

    /**
     * Delay between send rounds. A judgement call, not a measurement: nothing on the wire depends
     * on the rate, and at 20 bytes a datagram this is negligible either way. Slow enough that a
     * session does not flood its own logs, fast enough that a few seconds of a live session is
     * enough evidence for the exchange test.
     */
    private static final Duration CADENCE = Duration.ofMillis(100);

    /** How often {@link #logProgress(boolean)} samples the receiver's counters. */
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(1);

    /**
     * How long {@link #close()} waits for the receive loop to unwind. Long enough for a thread
     * already blocked on a closed socket to wake and log, short enough to be invisible against the
     * client's five-second SIGTERM grace.
     */
    private static final Duration RECEIVER_STOP_TIMEOUT = Duration.ofMillis(500);

    /** This game's FAF player id: stamped into every datagram, and named in the progress line. */
    private final int playerId;

    /** Delay between send rounds; {@link #CADENCE} outside tests. */
    private final Duration cadence;

    /** Progress sampling interval; {@link #PROGRESS_INTERVAL} outside tests. */
    private final Duration progressInterval;

    /** Endpoint each peer id is registered at, so an unchanged re-registration can be skipped. */
    private final Map<Integer, String> registered = new HashMap<>();

    /** Datagram count last reported per sender, so an idle sender logs nothing. */
    private final Map<Integer, Long> lastReported = new HashMap<>();

    /** The shared lobby socket; {@code null} until {@link #bind(int)} succeeds. */
    private DatagramSocket socket;

    /** Emits one datagram per registered peer per tick; {@code null} until bound. */
    private GameUdpSender sender;

    /** Reads peer datagrams off the same socket; {@code null} until bound. */
    private GameUdpReceiver receiver;

    /** Drives {@link #logProgress(boolean)}; {@code null} until bound. */
    private GameTicker progress;

    /** True once the cadence has been started by the first registered peer. */
    private boolean sending;

    /**
     * True once a bind was attempted and failed, so a later peer names the fault it actually hit.
     */
    private boolean bindFailed;

    /** True once {@link #close()} has run; every call afterwards is a no-op. */
    private boolean closed;

    /**
     * Creates a session for the game's own player id, with the production cadence.
     *
     * @param playerId this game's FAF player id, as the lobby assigned it
     */
    public GameTrafficSession(final int playerId) {
        this(playerId, CADENCE, PROGRESS_INTERVAL);
    }

    /**
     * Creates a session with explicit timings, so a test does not wait on the production ones.
     *
     * @param playerId this game's FAF player id
     * @param cadence delay between send rounds; must be positive
     * @param progressInterval how often the receiver's counters are sampled; must be positive
     */
    GameTrafficSession(
            final int playerId, final Duration cadence, final Duration progressInterval) {
        this.playerId = playerId;
        this.cadence = cadence;
        this.progressInterval = progressInterval;
    }

    /**
     * Binds the lobby socket and builds the sender and receiver over it.
     *
     * <p>Called on {@code CreateLobby} with the port that frame named — where the adapter
     * physically sends peer traffic — falling back to the game's {@code --lobby-port} only when the
     * frame carries no usable one; the lifecycle warns when the two disagree. A failure to bind is
     * logged and leaves this session inert rather than failing the transition.
     *
     * @param lobbyPort the UDP port the adapter forwards peer datagrams to
     */
    public synchronized void bind(final int lobbyPort) {
        if (closed || socket != null) {
            LOG.warn("ignoring bind on port {}: lobby socket already bound or closed", lobbyPort);
            return;
        }
        try {
            socket = new DatagramSocket(lobbyPort);
        } catch (SocketException e) {
            bindFailed = true;
            LOG.error(
                    "failed to bind lobby port {} ({}); this game will exchange no peer traffic",
                    lobbyPort,
                    e.getMessage());
            return;
        }
        bindFailed = false;
        sender = new GameUdpSender(playerId, socket, cadence);
        receiver = new GameUdpReceiver(socket);
        progress = GameTicker.realTime(progressInterval, () -> logProgress(false));
        receiver.start();
        // Started with the receiver, not with the cadence: it reports what arrives, which does not
        // depend on this game having a destination of its own yet. An idle sampling tick logs
        // nothing, so this costs one timer thread and no noise.
        progress.start();
        LOG.info("lobby socket bound on port {} for player {}", lobbyPort, playerId);
    }

    /**
     * Registers a peer as a traffic destination, starting the cadence on the first one.
     *
     * <p>Called with the {@code net_address} and {@code remote_player_id} of a {@code JoinGame} or
     * {@code ConnectToPeer} frame — the address of that peer's local relay socket inside the ICE
     * adapter. A repeat registration of an unchanged endpoint is skipped, because 3.2.2.5 resets
     * that peer's sequence on every registration and a reset stalls the receiving side's evidence
     * that the stream is advancing.
     *
     * @param netAddress the {@code host:port} the adapter supplied
     * @param peerId the remote player's id
     */
    public synchronized void registerPeer(final String netAddress, final int peerId) {
        if (closed) {
            return;
        }
        if (sender == null) {
            // Two different faults, and confusing them sends triage to the wrong place: a failed
            // bind is a port problem here, an unbound socket is a frame-ordering problem upstream.
            if (bindFailed) {
                LOG.warn(
                        "peer {} at {} announced but the lobby socket failed to bind; no traffic "
                                + "will reach it",
                        peerId,
                        netAddress);
            } else {
                LOG.warn(
                        "peer {} at {} announced before the lobby socket was bound; no traffic "
                                + "will reach it",
                        peerId,
                        netAddress);
            }
            return;
        }
        if (netAddress.equals(registered.get(peerId))) {
            LOG.debug(
                    "peer {} is already registered at {}; keeping its sequence",
                    peerId,
                    netAddress);
            return;
        }
        try {
            sender.registerPeer(netAddress, peerId);
        } catch (IllegalArgumentException e) {
            LOG.warn("ignoring peer {} with an unusable address {}", peerId, netAddress);
            return;
        }
        registered.put(peerId, netAddress);
        if (!sending) {
            sending = true;
            sender.start();
            LOG.info("peer traffic started: one datagram per peer every {} ms", cadence.toMillis());
        }
        LOG.info("sending peer traffic to player {} at {}", peerId, netAddress);
    }

    /**
     * Stops the cadence and closes the lobby socket, which ends the receive loop.
     *
     * <p>Run by the game's shutdown sequence, on every exit path. Logs one final progress line per
     * sender, closes the socket, then waits briefly for the receive loop to unwind so the
     * receiver's own totals line lands before this returns rather than on a daemon thread at some
     * arbitrary point afterwards. That matters beyond tidiness: the bootstrap stops the logging
     * context immediately after teardown, and a Gradle test worker treats output written after its
     * class finished as a fault of its own.
     *
     * <p>The wait is bounded and cannot deadlock: the receive thread touches nothing guarded by
     * this session's monitor, and the socket it is blocked on has already been closed. A receiver
     * that outlives the bound is left to the daemon-thread fate it had before.
     *
     * <p>Idempotent.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (sender != null) {
            sender.stop();
        }
        if (progress != null) {
            progress.stop();
        }
        if (socket != null) {
            logProgress(true);
            socket.close();
            awaitReceiverStopped();
        }
    }

    /**
     * Waits, briefly, for the receive loop to notice the closed socket and log its totals. Returns
     * at once if the receiver never started, and gives up rather than hanging teardown.
     */
    private void awaitReceiverStopped() {
        Thread thread = receiver == null ? null : receiver.receiveThread();
        if (thread == null) {
            return;
        }
        try {
            thread.join(RECEIVER_STOP_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive()) {
            LOG.warn(
                    "receive loop still running {} ms after its socket closed",
                    RECEIVER_STOP_TIMEOUT.toMillis());
        }
    }

    /**
     * Logs one line per sender whose datagram count has moved since the last sample.
     *
     * @param force log every known sender, whether or not its count moved
     */
    private synchronized void logProgress(final boolean force) {
        if (receiver == null) {
            return;
        }
        for (int senderId : receiver.senders()) {
            long received = receiver.received(senderId);
            Long previous = lastReported.get(senderId);
            if (!force && previous != null && previous == received) {
                continue;
            }
            lastReported.put(senderId, received);
            LOG.info(
                    "player {} peer traffic from player {}: {} datagrams, highest sequence {}, "
                            + "gaps {}",
                    playerId,
                    senderId,
                    received,
                    receiver.highestSequence(senderId),
                    receiver.discontinuities(senderId));
        }
    }

    /**
     * Whether this session has been torn down.
     *
     * <p>The public half of {@link #close()}: the shutdown sequence's contract is that the cadence
     * has stopped and the lobby socket is closed once this reads {@code true}.
     *
     * @return {@code true} once {@link #close()} has run
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * The shared lobby socket, so a test can assert it is bound and later closed.
     *
     * @return the socket, or {@code null} if it was never bound
     */
    synchronized DatagramSocket socket() {
        return socket;
    }

    /**
     * The receiver reading that socket, so a test can reach its counters and its thread.
     *
     * @return the receiver, or {@code null} if the socket was never bound
     */
    synchronized GameUdpReceiver receiver() {
        return receiver;
    }

    /**
     * Whether the cadence has started.
     *
     * @return {@code true} once a peer has been registered and the sender started
     */
    synchronized boolean isSending() {
        return sending;
    }
}
