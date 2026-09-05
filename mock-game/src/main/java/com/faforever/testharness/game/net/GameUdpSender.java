package com.faforever.testharness.game.net;

import com.faforever.testharness.game.activity.GameTicker;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the mock game's simulated peer traffic as UDP datagrams to the ICE adapter (WBS-3.2.2.5).
 *
 * <p>Source-verified addressing (java-ice-adapter): the game binds one UDP socket on the lobby port
 * from {@code CreateLobby}, and sends each peer's traffic to the local per-peer address the adapter
 * supplies in {@code JoinGame} / {@code ConnectToPeer} ({@code "127.0.0.1:<port>"}). The adapter
 * then proxies each datagram opaquely into that peer's ICE channel. Register a destination per peer
 * with {@link #registerPeer(String, int)} (the {@code net_address} and {@code remote_player_id}
 * from those frames), then {@link #start()} to emit a {@link GameDatagram} to every peer once per
 * tick.
 *
 * <p>One socket, N destinations, one timer. The socket is owned here and shared with the receiver
 * (WBS-3.2.2.6, which reads from the <em>same</em> socket next sprint — see {@link #socket()}); the
 * caller binds it on the lobby port. Cadence is driven by a {@link GameTicker} (WBS-3.2.3). The
 * FSM's LIVE phase calls {@link #start()} / {@link #stop()}.
 *
 * <p>Send failures are logged and dropped (log-and-drop convention): a dead peer or a closed socket
 * cannot kill the sender or the game. Sequence numbers are per-peer and monotonic so the receiving
 * side can assert delivery and ordering.
 *
 * <p><b>Fault injection (WBS-5.1).</b> A drop percentage suppresses that fraction of outbound
 * datagrams at the same point a send failure is swallowed, so a degraded link can be exercised
 * without {@code tc}, elevated privileges, or a specific operating system. It defaults to zero, and
 * at zero this class behaves exactly as it did before the flag existed. The sequence number is
 * stamped and advanced <em>before</em> the drop decision: {@link GameUdpReceiver} counts forward
 * gaps against that sequence, so a drop that skipped the increment would produce an unbroken stream
 * at the receiver and the injected fault would be invisible. The drop is decided per peer per
 * round, which is what keeps a loss attributable to one sender in the receiver's counters.
 */
public final class GameUdpSender {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameUdpSender.class);

    /** Upper bound of the drop percentage, and the modulus the per-datagram draw is taken over. */
    private static final int PERCENT = 100;

    /** This mock game's player id, stamped into every datagram. */
    private final int senderPlayerId;

    /** The shared UDP socket, bound by the caller on the lobby port. */
    private final DatagramSocket socket;

    /** Drives one {@link #sendRound()} per tick at the configured cadence. */
    private final GameTicker ticker;

    /** Registered peer destinations by player id; each carries its own monotonic sequence. */
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();

    /** Percentage of outbound datagrams to suppress; {@code 0} sends everything (WBS-5.1). */
    private final int dropPercent;

    /**
     * A peer destination and its per-peer send sequence.
     *
     * @param address the resolved UDP destination
     * @param sequence the next sequence number to stamp on a datagram to this peer
     */
    private record Peer(InetSocketAddress address, AtomicLong sequence) {}

    /**
     * Creates a sender that emits every datagram. Equivalent to {@link #GameUdpSender(int,
     * DatagramSocket, Duration, int)} with a drop percentage of zero.
     *
     * @param senderPlayerId this game's FAF player id, stamped into each datagram
     * @param socket the UDP socket to send from — bound by the caller on the lobby port and shared
     *     with the receiver card; must not be {@code null}
     * @param cadence the delay between send rounds; must be positive
     * @throws IllegalArgumentException if {@code cadence} is zero or negative
     */
    public GameUdpSender(
            final int senderPlayerId, final DatagramSocket socket, final Duration cadence) {
        this(senderPlayerId, socket, cadence, 0);
    }

    /**
     * Creates a sender over an already-bound socket, suppressing {@code dropPercent} of its
     * outbound datagrams.
     *
     * @param senderPlayerId this game's FAF player id, stamped into each datagram
     * @param socket the UDP socket to send from — bound by the caller on the lobby port and shared
     *     with the receiver card; must not be {@code null}
     * @param cadence the delay between send rounds; must be positive
     * @param dropPercent percentage of datagrams to suppress, {@code 0}–{@code 100}; {@code 0}
     *     sends everything, which is the default and the pre-WBS-5.1 behaviour
     * @throws IllegalArgumentException if {@code cadence} is zero or negative, or if {@code
     *     dropPercent} is outside {@code 0}–{@code 100}
     */
    public GameUdpSender(
            final int senderPlayerId,
            final DatagramSocket socket,
            final Duration cadence,
            final int dropPercent) {
        if (dropPercent < 0 || dropPercent > PERCENT) {
            throw new IllegalArgumentException(
                    "dropPercent must be between 0 and 100: " + dropPercent);
        }
        this.senderPlayerId = senderPlayerId;
        this.socket = Objects.requireNonNull(socket, "socket");
        this.dropPercent = dropPercent;
        this.ticker = GameTicker.realTime(cadence, this::sendRound);
    }

    /**
     * Registers (or updates) a peer destination, as parsed from a {@code JoinGame} / {@code
     * ConnectToPeer} frame's {@code net_address} and {@code remote_player_id}.
     *
     * @param netAddress the {@code host:port} the adapter supplied (e.g. {@code "127.0.0.1:6112"})
     * @param playerId the remote player's id
     * @throws IllegalArgumentException if {@code netAddress} is not a valid {@code host:port}
     */
    public void registerPeer(final String netAddress, final int playerId) {
        peers.put(playerId, new Peer(parseAddress(netAddress), new AtomicLong()));
    }

    /**
     * Starts emitting a datagram to every registered peer once per cadence. Idempotent; a no-op
     * once {@link #stop()} has run.
     */
    public void start() {
        ticker.start();
    }

    /**
     * Stops emission. Idempotent and terminal — no new send round starts after this returns (an
     * in-flight round may complete concurrently), and {@link #start()} afterwards is a no-op. Does
     * not close the socket: it is shared with the receiver, so its lifecycle belongs to the owner
     * (the shutdown sequence).
     */
    public void stop() {
        ticker.stop();
    }

    /**
     * The shared UDP socket, exposed so the receiver card (WBS-3.2.2.6) can read from the same
     * socket rather than opening a second one.
     *
     * @return the socket this sender emits from
     */
    public DatagramSocket socket() {
        return socket;
    }

    /**
     * Sends one datagram to each registered peer, advancing that peer's sequence. A failed send is
     * logged and skipped so one dead peer or a closed socket cannot stop the round or the game.
     * With a drop percentage configured (WBS-5.1), that fraction of datagrams is suppressed here
     * instead of sent — after the sequence has been stamped, so the loss shows up as a gap at the
     * receiver. Package-private so tests can drive a deterministic round without the timer.
     */
    void sendRound() {
        for (Map.Entry<Integer, Peer> entry : peers.entrySet()) {
            Peer peer = entry.getValue();
            GameDatagram datagram =
                    new GameDatagram(
                            senderPlayerId,
                            peer.sequence().getAndIncrement(),
                            System.currentTimeMillis());
            if (shouldDrop()) {
                // DEBUG, not WARN: an injected fault is the operator's own doing, and at a high
                // percentage a per-datagram WARN would bury everything else in the run. The
                // receiver's gap counters are the intended measurement; this line is for
                // attributing a specific gap when reading one run's log.
                LOG.debug(
                        "dropping datagram seq={} to peer {} at {} ({}% fault injection)",
                        datagram.sequence(), entry.getKey(), peer.address(), dropPercent);
                continue;
            }
            byte[] payload = datagram.toBytes();
            try {
                socket.send(new DatagramPacket(payload, payload.length, peer.address()));
            } catch (IOException e) {
                LOG.warn(
                        "failed to send datagram to peer {} at {}: {}",
                        entry.getKey(),
                        peer.address(),
                        e.getMessage());
            }
        }
    }

    /**
     * Whether this datagram should be suppressed, drawn independently per peer per round.
     *
     * <p>Short-circuits at zero so the default path draws no random number at all and is byte-for-
     * byte the pre-WBS-5.1 behaviour. {@code nextInt(100)} yields {@code 0}–{@code 99}, so {@code
     * 100} drops everything and no value in between is unreachable.
     *
     * @return {@code true} if the datagram should be dropped rather than sent
     */
    private boolean shouldDrop() {
        return dropPercent > 0 && ThreadLocalRandom.current().nextInt(PERCENT) < dropPercent;
    }

    private static InetSocketAddress parseAddress(final String netAddress) {
        int colon = netAddress.lastIndexOf(':');
        if (colon <= 0 || colon == netAddress.length() - 1) {
            throw new IllegalArgumentException("not a host:port address: " + netAddress);
        }
        String host = netAddress.substring(0, colon);
        final int port;
        try {
            port = Integer.parseInt(netAddress.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a host:port address: " + netAddress, e);
        }
        return new InetSocketAddress(host, port);
    }
}
