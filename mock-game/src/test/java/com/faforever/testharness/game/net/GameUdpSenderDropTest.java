package com.faforever.testharness.game.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests {@link GameUdpSender}'s WBS-5.1 drop percentage: the lossy-link half of the harness's
 * network fault injection.
 *
 * <p>Every case drives {@link GameUdpSender#sendRound()} directly rather than the ticker, so the
 * number of send attempts is exact and the only variable is the drop decision. {@link
 * GameUdpSenderTest} covers the same sender with the flag off, and is the control for all of this.
 */
@Timeout(30)
final class GameUdpSenderDropTest {

    /**
     * Rounds driven by the proportionality case. At {@code p = 0.5} the standard deviation of the
     * observed rate over this many draws is about 2 percentage points, so the ±15-point band below
     * sits roughly seven deviations out — wide enough never to flake, narrow enough that a sender
     * ignoring the percentage (0 or 100) fails every time.
     */
    private static final int ROUNDS = 600;

    /** How far the observed drop rate may sit from the configured one, in percentage points. */
    private static final int TOLERANCE_POINTS = 15;

    private DatagramSocket senderSocket;
    private DatagramSocket peer;
    private GameUdpSender sender;

    @BeforeEach
    void setUp() throws Exception {
        senderSocket = new DatagramSocket(0);
        peer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        // The proportionality case sends hundreds of datagrams; the default receive buffer is not
        // guaranteed to hold them all, and a kernel-dropped datagram would read as an injected one.
        peer.setReceiveBufferSize(1 << 20);
    }

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.stop();
        }
        senderSocket.close();
        peer.close();
    }

    private static String local(final DatagramSocket socket) {
        return "127.0.0.1:" + socket.getLocalPort();
    }

    /** Receives one datagram within {@code timeoutMs}, or returns {@code null} on timeout. */
    private static GameDatagram receive(final DatagramSocket socket, final int timeoutMs)
            throws IOException {
        socket.setSoTimeout(timeoutMs);
        byte[] buffer = new byte[64];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
            return GameDatagram.parse(packet.getData(), packet.getLength());
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    /** A drop percentage of 100 suppresses everything; nothing reaches the peer at all. */
    @Test
    void oneHundredPercentDropsEveryDatagram() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50), 100);
        sender.registerPeer(local(peer), 7);

        for (int i = 0; i < 20; i++) {
            sender.sendRound();
        }

        assertNull(receive(peer, 300), "every datagram should have been suppressed");
    }

    /** The default is off, and stays off: zero drops nothing, round after round. */
    @Test
    void zeroPercentDropsNothing() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50), 0);
        sender.registerPeer(local(peer), 7);

        for (int i = 0; i < 20; i++) {
            sender.sendRound();
            GameDatagram received = receive(peer, 1000);
            assertNotNull(received, "round " + i + " was dropped with the flag off");
            assertEquals(i, received.sequence(), "sequence must run unbroken with no drops");
        }
    }

    /**
     * The sequence still advances across a dropped datagram, so the loss lands in {@link
     * GameUdpReceiver}'s per-sender counters as a forward gap attributable to this sender. This is
     * the one way the whole flag could quietly not work: suppress the increment as well as the send
     * and the peer sees an unbroken stream, with the fault invisible to the receiver's
     * discontinuity counter and to every test built on it.
     *
     * <p>The discriminator is the highest sequence the receiver saw. With the increment in the
     * right place it approaches {@code ROUNDS - 1} however many datagrams were dropped; with the
     * increment skipped it can only ever reach {@code received - 1}, because the numbers the
     * receiver never saw were never issued.
     */
    @Test
    void droppedDatagramsStillConsumeTheirSequenceNumberAndShowUpAsReceiverGaps() throws Exception {
        int peerPlayerId = 7;
        // No stop(): the receiver's thread is a daemon that ends when the socket closes, which
        // tearDown does.
        GameUdpReceiver receiver = new GameUdpReceiver(peer);
        receiver.start();
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50), 50);
        sender.registerPeer(local(peer), peerPlayerId);

        for (int i = 0; i < ROUNDS; i++) {
            sender.sendRound();
        }
        assertTrue(
                await(() -> receiver.highestSequence(42) >= ROUNDS - 10, 5000),
                "the receiver never saw a sequence near the end of the run; highest was "
                        + receiver.highestSequence(42));

        long received = receiver.received(42);
        assertTrue(received < ROUNDS, "nothing was dropped, so this proves nothing");
        assertTrue(
                receiver.highestSequence(42) >= received,
                "highest sequence "
                        + receiver.highestSequence(42)
                        + " is below the "
                        + received
                        + " datagrams received, so drops skipped their sequence number");
        assertTrue(
                receiver.discontinuities(42) > 0,
                "the drops produced no forward gap in the receiver's counters");
    }

    /** Polls {@code condition} until it holds or {@code timeoutMillis} elapses. */
    private static boolean await(final BooleanSupplier condition, final int timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /**
     * A configured percentage produces a proportionate reduction in datagrams reaching the peer,
     * over a bounded run driven through {@code sendRound()}.
     */
    @Test
    void aConfiguredPercentageProducesAProportionateReduction() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50), 50);
        sender.registerPeer(local(peer), 7);

        for (int i = 0; i < ROUNDS; i++) {
            sender.sendRound();
        }

        int arrived = 0;
        while (receive(peer, 200) != null) {
            arrived++;
        }
        int observedDropPercent = (ROUNDS - arrived) * 100 / ROUNDS;

        assertTrue(
                Math.abs(observedDropPercent - 50) <= TOLERANCE_POINTS,
                "dropped "
                        + observedDropPercent
                        + "% of "
                        + ROUNDS
                        + " datagrams, expected about 50% (±"
                        + TOLERANCE_POINTS
                        + " points)");
    }

    /** Per-peer draws: one peer's losses do not decide another's, so a gap names one sender. */
    @Test
    void eachPeerIsDrawnIndependently() throws Exception {
        try (DatagramSocket secondPeer =
                new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50), 100);
            sender.registerPeer(local(peer), 7);
            sender.registerPeer(local(secondPeer), 8);

            sender.sendRound();

            assertNull(receive(peer, 300), "peer 7 should have been dropped");
            assertNull(receive(secondPeer, 300), "peer 8 should have been dropped");
        }
    }

    /** A percentage outside 0-100 is a typo, not a mode; it is rejected at construction. */
    @Test
    void outOfRangePercentagesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameUdpSender(42, senderSocket, Duration.ofMillis(50), -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameUdpSender(42, senderSocket, Duration.ofMillis(50), 101));
    }
}
