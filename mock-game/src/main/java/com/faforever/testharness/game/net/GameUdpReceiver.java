package com.faforever.testharness.game.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the mock game's simulated peer traffic from the ICE adapter (WBS-3.2.2.6). The receiving
 * half of {@link GameUdpSender}: peer datagrams arrive on the game's single lobby socket, forwarded
 * raw by the adapter from one local socket per peer.
 *
 * <p>Source-verified addressing (java-ice-adapter {@code Peer.java}): the adapter creates a {@code
 * DatagramSocket} per peer and repackages each inbound ICE datagram to {@code
 * 127.0.0.1:<lobbyPort>} unchanged, adding no framing, acknowledgement, or reliability. So a single
 * blocking receive on the shared lobby socket, decoding raw {@link GameDatagram} payloads, is the
 * real contract.
 *
 * <p>One socket, one daemon thread, blocking receive. The socket is the <em>same</em> one {@link
 * GameUdpSender} emits from, exposed by {@link GameUdpSender#socket()}; this receiver reads it but
 * never opens another and never closes it — that lifecycle belongs to the shutdown sequence
 * (WBS-3.2.5.2). {@link #start()} spins the loop; it ends on its own when the socket's owner closes
 * the socket.
 *
 * <p>Attribution is by the payload's {@code senderId} (ours and simpler than an endpoint-to-peer
 * demux, which nothing here needs), with the datagram's source endpoint logged alongside. Per
 * sender it keeps datagrams received, highest sequence seen, and sequence discontinuities, so the
 * two-peer exchange test (WBS-4.3.2) and the Phase 5 fault tests can verify traffic from log output
 * alone. Gaps are data, not errors: a discontinuity is recorded and the loop continues, never
 * blocking or retrying. Malformed datagrams are logged and dropped (the sender's log-and-drop
 * convention) — a UDP port receives garbage in the wild and must never crash.
 *
 * <p>No reply logic and no delivery guarantees, because the real path has neither.
 */
public final class GameUdpReceiver {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameUdpReceiver.class);

    /**
     * Receive-buffer size. Comfortably above a {@link GameDatagram} ({@value GameDatagram#BYTES}
     * bytes) and any stray oversized garbage the socket might receive; the adapter's proxied ICE
     * payloads sit well under 1&nbsp;KB.
     */
    private static final int BUFFER_BYTES = 2048;

    /**
     * The shared UDP socket, bound by the caller on the lobby port and owned by the sender side.
     */
    private final DatagramSocket socket;

    /** Per-sender counters keyed by payload {@code senderId}; read by tests on another thread. */
    private final Map<Integer, SenderStats> stats = new ConcurrentHashMap<>();

    /** The single receive thread; {@code null} until {@link #start()}. */
    private volatile Thread thread;

    /**
     * Per-sender counters. Held in {@link AtomicLong}s so the values written by the single receive
     * thread are visible to a test (or observer) reading them from another thread.
     */
    static final class SenderStats {

        /** Datagrams successfully decoded and attributed to this sender. */
        private final AtomicLong received = new AtomicLong();

        /** Highest sequence number seen from this sender; {@code -1} until the first datagram. */
        private final AtomicLong highestSequence = new AtomicLong(-1);

        /** Count of forward gaps: datagrams whose sequence skipped past the expected next one. */
        private final AtomicLong discontinuities = new AtomicLong();
    }

    /**
     * Creates a receiver over the sender's already-bound shared socket.
     *
     * @param socket the UDP socket to read from — the one from {@link GameUdpSender#socket()},
     *     bound by the caller on the lobby port; must not be {@code null}. Not closed by this
     *     receiver.
     */
    public GameUdpReceiver(final DatagramSocket socket) {
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    /**
     * Starts the blocking receive loop on one daemon thread. Idempotent: a second call while the
     * loop is running is a no-op. The loop runs until the socket's owner closes the socket.
     */
    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        Thread t = new Thread(this::receiveLoop, "game-udp-receiver");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Datagrams received and attributed to {@code senderId}.
     *
     * @param senderId a payload sender id
     * @return the count, or {@code 0} if nothing has been received from that sender
     */
    public long received(final int senderId) {
        SenderStats s = stats.get(senderId);
        return s == null ? 0L : s.received.get();
    }

    /**
     * Highest sequence number seen from {@code senderId}.
     *
     * @param senderId a payload sender id
     * @return the highest sequence, or {@code -1} if nothing has been received from that sender
     */
    public long highestSequence(final int senderId) {
        SenderStats s = stats.get(senderId);
        return s == null ? -1L : s.highestSequence.get();
    }

    /**
     * Sequence discontinuities (forward gaps) counted from {@code senderId}. Reordering does not
     * count; only a sequence that skips past the expected next one does.
     *
     * @param senderId a payload sender id
     * @return the gap count, or {@code 0} if nothing has been received from that sender
     */
    public long discontinuities(final int senderId) {
        SenderStats s = stats.get(senderId);
        return s == null ? 0L : s.discontinuities.get();
    }

    /**
     * The sender ids seen so far.
     *
     * @return an unmodifiable view of the ids with recorded counters
     */
    public Set<Integer> senders() {
        return Set.copyOf(stats.keySet());
    }

    /**
     * The receive thread, or {@code null} before {@link #start()}. Package-private so a test can
     * {@link Thread#join(long)} on it to assert the loop ends within a bound after the socket
     * closes.
     *
     * @return the receive thread
     */
    Thread receiveThread() {
        return thread;
    }

    /**
     * The blocking receive loop. Runs until the socket is closed by its owner, which surfaces as an
     * {@link IOException} on a closed socket and is treated as normal termination. Every other
     * error is confined to the offending datagram so loss, reordering, and garbage never stop the
     * loop.
     */
    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (true) {
            packet.setLength(buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (socket.isClosed()) {
                    // The owner closed the shared socket: normal termination, not a failure.
                    break;
                }
                // A transient receive error on a still-open socket: log and keep listening.
                LOG.warn("receive error, continuing to listen: {}", e.toString());
                continue;
            }
            handle(packet);
        }
        logTotals();
    }

    // Decodes and records one packet, or logs-and-drops it if the payload is malformed.
    private void handle(final DatagramPacket packet) {
        final GameDatagram datagram;
        try {
            datagram = GameDatagram.parse(packet.getData(), packet.getLength());
        } catch (RuntimeException e) {
            LOG.warn(
                    "dropping malformed datagram ({} bytes) from {}: {}",
                    packet.getLength(),
                    packet.getSocketAddress(),
                    e.getMessage());
            return;
        }
        record(datagram, packet.getSocketAddress());
    }

    // Updates the sender's counters and logs at the appropriate level.
    private void record(final GameDatagram datagram, final SocketAddress source) {
        int senderId = datagram.senderId();
        long sequence = datagram.sequence();

        SenderStats existing = stats.get(senderId);
        boolean first = existing == null;
        SenderStats s = first ? new SenderStats() : existing;
        if (first) {
            stats.put(senderId, s);
        }

        long count = s.received.incrementAndGet();
        long previousHighest = s.highestSequence.get();
        if (previousHighest >= 0 && sequence > previousHighest + 1) {
            s.discontinuities.incrementAndGet();
        }
        if (sequence > previousHighest) {
            s.highestSequence.set(sequence);
        }

        if (first) {
            LOG.info("first datagram from sender {} (seq {}) at {}", senderId, sequence, source);
        }
        LOG.debug(
                "datagram from sender {}: seq {} (received {}, highest {}, gaps {}) from {}",
                senderId,
                sequence,
                count,
                s.highestSequence.get(),
                s.discontinuities.get(),
                source);
    }

    /** Logs one totals line per sender as the loop ends. */
    private void logTotals() {
        if (stats.isEmpty()) {
            LOG.info("game UDP receiver stopped; no datagrams received");
            return;
        }
        stats.forEach(
                (senderId, s) ->
                        LOG.info(
                                "game UDP receiver stopped; sender {} totals: received {}, "
                                        + "highest sequence {}, discontinuities {}",
                                senderId,
                                s.received.get(),
                                s.highestSequence.get(),
                                s.discontinuities.get()));
    }
}
