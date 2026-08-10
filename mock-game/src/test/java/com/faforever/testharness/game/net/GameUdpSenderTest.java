package com.faforever.testharness.game.net;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link GameUdpSender}: addressing, per-peer sequencing, start/stop, and log-and-drop.
 */
@Timeout(20)
final class GameUdpSenderTest {

    private DatagramSocket senderSocket;
    private DatagramSocket peerA;
    private DatagramSocket peerB;
    private GameUdpSender sender;

    @BeforeEach
    void setUp() throws Exception {
        senderSocket = new DatagramSocket(0);
        peerA = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        peerB = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
    }

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.stop();
        }
        senderSocket.close();
        peerA.close();
        peerB.close();
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

    /** Drains all currently-buffered datagrams, returning the count. */
    private static int drain(final DatagramSocket socket) throws IOException {
        int count = 0;
        while (receive(socket, 60) != null) {
            count++;
        }
        return count;
    }

    @Test
    void sendRoundGoesToThePeerAddressWithIncrementingSequence() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50));
        sender.registerPeer(local(peerA), 7);

        sender.sendRound();
        GameDatagram first = receive(peerA, 1000);
        assertNotNull(first, "peer should receive a datagram");
        assertEquals(42, first.senderId(), "senderId is our player id");
        assertEquals(0L, first.sequence(), "first sequence is 0");

        sender.sendRound();
        assertEquals(1L, receive(peerA, 1000).sequence(), "sequence increments per peer");
    }

    @Test
    void perPeerSequencesAreIndependent() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50));
        sender.registerPeer(local(peerA), 7);
        sender.registerPeer(local(peerB), 8);

        sender.sendRound();
        sender.sendRound();

        assertEquals(0L, receive(peerA, 1000).sequence());
        assertEquals(1L, receive(peerA, 1000).sequence());
        assertEquals(0L, receive(peerB, 1000).sequence());
        assertEquals(1L, receive(peerB, 1000).sequence());
    }

    @Test
    void noTrafficBeforeStart() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(20));
        sender.registerPeer(local(peerA), 7);

        // Never started and sendRound never called: the peer must see nothing.
        assertNull(receive(peerA, 200), "no datagram should be emitted before start()");
    }

    @Test
    void startEmitsAtCadenceThenStopHaltsAndIsIdempotent() throws Exception {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(20));
        sender.registerPeer(local(peerA), 7);

        sender.start();
        Thread.sleep(250);
        sender.stop();

        // Drain only after stop, so the ticker is no longer racing us (draining while it emits
        // every 20ms would never reach the receive timeout).
        int emitted = drain(peerA);
        assertTrue(emitted >= 3, "a ~20ms cadence should emit several in 250ms; got " + emitted);

        Thread.sleep(120);
        assertEquals(0, drain(peerA), "no traffic after stop()");

        assertDoesNotThrow(sender::stop, "stop() is idempotent");
    }

    @Test
    void sendFailureIsLoggedAndDoesNotThrow() throws Exception {
        DatagramSocket failing =
                new DatagramSocket(0) {
                    @Override
                    public void send(final DatagramPacket p) throws IOException {
                        throw new IOException("boom");
                    }
                };
        sender = new GameUdpSender(42, failing, Duration.ofMillis(50));
        sender.registerPeer(local(peerA), 7);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger(GameUdpSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(sender::sendRound, "a send failure must not propagate");
            assertTrue(
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getLevel() == Level.WARN
                                                    && e.getFormattedMessage().contains("failed")),
                    "the failure should be logged at WARN; events: " + appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            failing.close();
        }
    }

    @Test
    void rejectsMalformedPeerAddress() {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50));
        assertThrows(IllegalArgumentException.class, () -> sender.registerPeer("no-colon", 7));
        assertThrows(IllegalArgumentException.class, () -> sender.registerPeer("127.0.0.1:abc", 7));
        assertThrows(IllegalArgumentException.class, () -> sender.registerPeer("127.0.0.1:", 7));
    }

    @Test
    void rejectsNonPositiveCadence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GameUdpSender(42, senderSocket, Duration.ZERO));
    }

    @Test
    void socketGetterExposesTheSharedSocket() {
        sender = new GameUdpSender(42, senderSocket, Duration.ofMillis(50));
        assertSame(senderSocket, sender.socket(), "the receiver card reads from this same socket");
    }
}
