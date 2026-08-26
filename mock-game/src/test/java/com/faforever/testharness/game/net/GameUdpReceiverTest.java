package com.faforever.testharness.game.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link GameUdpReceiver}: decode-and-attribute, gap counting, malformed drop, prompt
 * shutdown on socket close, and concurrent send/receive on the shared socket. A plain local {@link
 * DatagramSocket} stands in for the ICE adapter, mirroring the {@link GameUdpSenderTest} style.
 */
@Timeout(20)
final class GameUdpReceiverTest {

    /** The game's single lobby socket, shared by sender and receiver. */
    private DatagramSocket lobbySocket;

    /** Stands in for the adapter's per-peer socket that forwards inbound data to the lobby. */
    private DatagramSocket adapterSocket;

    private GameUdpReceiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        lobbySocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        adapterSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
    }

    @AfterEach
    void tearDown() {
        // The receiver never closes the shared socket; the owner (this test) does.
        lobbySocket.close();
        adapterSocket.close();
    }

    /** Address of the game's lobby socket, where the adapter forwards peer datagrams. */
    private InetSocketAddress lobby() {
        return new InetSocketAddress("127.0.0.1", lobbySocket.getLocalPort());
    }

    /** Sends {@code payload} to the lobby socket from the fake adapter socket. */
    private void forward(final byte[] payload) throws Exception {
        adapterSocket.send(new DatagramPacket(payload, payload.length, lobby()));
    }

    /** Sends one encoded {@link GameDatagram} to the lobby socket. */
    private void forward(final int senderId, final long sequence) throws Exception {
        forward(new GameDatagram(senderId, sequence, System.currentTimeMillis()).toBytes());
    }

    /** Polls {@code condition} up to {@code timeoutMs}, returning whether it became true. */
    private static boolean await(final BooleanSupplier condition, final long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @Test
    void receivesDecodesAndAttributesBySender() throws Exception {
        receiver = new GameUdpReceiver(lobbySocket);
        receiver.start();

        forward(42, 0);
        forward(42, 1);
        forward(7, 0);

        assertTrue(
                await(() -> receiver.received(42) == 2 && receiver.received(7) == 1, 2000),
                "both senders' datagrams should be decoded and attributed");
        assertEquals(1L, receiver.highestSequence(42), "highest sequence tracked per sender");
        assertEquals(0L, receiver.highestSequence(7));
        assertEquals(0L, receiver.discontinuities(42), "in-order traffic has no gaps");
        assertTrue(receiver.senders().containsAll(java.util.Set.of(42, 7)));
    }

    @Test
    void skippedSequenceIsCountedAsAGap() throws Exception {
        receiver = new GameUdpReceiver(lobbySocket);
        receiver.start();

        forward(42, 0);
        forward(42, 1);
        forward(42, 3); // 2 is skipped

        assertTrue(await(() -> receiver.received(42) == 3, 2000), "all three should arrive");
        assertEquals(1L, receiver.discontinuities(42), "the skipped sequence is one gap");
        assertEquals(3L, receiver.highestSequence(42), "highest still advances across the gap");
    }

    @Test
    void malformedDatagramIsDroppedAndLoopContinues() throws Exception {
        receiver = new GameUdpReceiver(lobbySocket);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger(GameUdpReceiver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        try {
            receiver.start();

            forward(new byte[] {1, 2, 3}); // too short to decode
            forward(42, 0); // a good one after the garbage

            assertTrue(
                    await(() -> receiver.received(42) == 1, 2000),
                    "a valid datagram after garbage must still be received");
            assertTrue(
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getLevel() == Level.WARN
                                                    && e.getFormattedMessage()
                                                            .contains("malformed")),
                    "the malformed datagram should be logged at WARN; events: " + appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void closingTheSocketEndsTheLoopWithinABound() throws Exception {
        receiver = new GameUdpReceiver(lobbySocket);
        receiver.start();

        forward(42, 0);
        assertTrue(await(() -> receiver.received(42) == 1, 2000), "receiver should be running");

        Thread loop = receiver.receiveThread();
        assertNotNull(loop, "start() spun a receive thread");

        lobbySocket.close(); // the owner closes the shared socket
        loop.join(2000);
        assertFalse(loop.isAlive(), "closing the socket must end the receive thread promptly");
    }

    @Test
    void doesNotCloseTheSharedSocket() throws Exception {
        receiver = new GameUdpReceiver(lobbySocket);
        receiver.start();

        forward(42, 0);
        assertTrue(await(() -> receiver.received(42) == 1, 2000));

        assertFalse(lobbySocket.isClosed(), "the receiver must never close the shared socket");
    }

    @Test
    void concurrentSendAndReceiveOnTheSharedSocket() throws Exception {
        // The sender emits on the shared socket while the receiver reads it: a real peer sits at
        // adapterSocket, and the receiver observes the peer's datagrams looped back to the lobby.
        receiver = new GameUdpReceiver(lobbySocket);
        receiver.start();

        int peerPlayerId = 99;
        GameUdpSender sender = new GameUdpSender(42, lobbySocket, Duration.ofMillis(10));
        sender.registerPeer("127.0.0.1:" + adapterSocket.getLocalPort(), peerPlayerId);
        try {
            sender.start();

            // While the sender emits to the peer, the peer forwards its own traffic to the lobby;
            // both directions run on the one shared socket without stalling.
            for (long seq = 0; seq < 5; seq++) {
                adapterSocket.send(
                        new DatagramPacket(
                                new GameDatagram(7, seq, System.currentTimeMillis()).toBytes(),
                                GameDatagram.BYTES,
                                lobby()));
                Thread.sleep(10);
            }

            assertTrue(
                    await(() -> receiver.received(7) >= 5, 3000),
                    "the receiver should observe the peer's datagrams while the sender emits");

            // And the sender kept running untouched: the peer socket saw the sender's datagrams.
            adapterSocket.setSoTimeout(1000);
            byte[] buffer = new byte[GameDatagram.BYTES];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            adapterSocket.receive(packet);
            GameDatagram fromSender = GameDatagram.parse(packet.getData(), packet.getLength());
            assertEquals(
                    42, fromSender.senderId(), "the sender kept emitting on the shared socket");
        } finally {
            sender.stop();
        }
    }

    @Test
    void startIsIdempotent() throws Exception {
        receiver = new GameUdpReceiver(lobbySocket);
        receiver.start();
        Thread first = receiver.receiveThread();
        receiver.start();
        assertEquals(first, receiver.receiveThread(), "a second start() reuses the running loop");
    }

    @Test
    void rejectsNullSocket() {
        assertThrows(NullPointerException.class, () -> new GameUdpReceiver(null));
    }
}
