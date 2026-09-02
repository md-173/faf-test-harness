package com.faforever.testharness.game.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * The traffic session on its own (WBS-4.3.2): no state machine, no adapter, plain sockets standing
 * in for the adapter's per-peer relay. The lifecycle's side of the wiring is covered by {@code
 * LifecycleTrafficWiringTest}; this class owns everything that needs to see the session's
 * internals.
 *
 * <p>Timings are the test constructor's, not production's, so the whole class runs in about a
 * second. The class-level timeout is a backstop: every wait below is individually bounded.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class GameTrafficSessionTest {

    /** This game's player id, stamped into every datagram it sends. */
    private static final int OWN_PLAYER_ID = 4242;

    /** The peer's player id, as a lobby would have assigned it. */
    private static final int PEER_PLAYER_ID = 77;

    /** Send cadence under test — fast enough that a few rounds cost milliseconds. */
    private static final Duration TEST_CADENCE = Duration.ofMillis(20);

    /** Progress sampling interval under test. */
    private static final Duration TEST_PROGRESS_INTERVAL = Duration.ofMillis(50);

    /** Receive buffer for the stub peer: comfortably above one {@link GameDatagram}. */
    private static final int RECEIVE_BUFFER_BYTES = 256;

    /** Budget for a datagram to make the loopback trip. */
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(5);

    /** Budget for a log line to appear. */
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(5);

    /** Budget for the receive loop to unwind once the socket closes. */
    private static final Duration LOOP_END_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How long "nothing arrives" is observed for. Several cadences, so a cadence that is still
     * running has every chance to prove it.
     */
    private static final Duration QUIET_WINDOW = Duration.ofMillis(300);

    /** The session under test. */
    private GameTrafficSession session;

    /** Stands in for the ICE adapter's per-peer relay socket: what the game sends to. */
    private DatagramSocket peer;

    /** Root logger the appender is attached to. */
    private Logger root;

    /** Captures log records so the progress and failure lines can be asserted. */
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws IOException {
        session = new GameTrafficSession(OWN_PLAYER_ID, TEST_CADENCE, TEST_PROGRESS_INTERVAL);
        peer = new DatagramSocket(0);
        peer.setSoTimeout((int) RECEIVE_TIMEOUT.toMillis());

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // Written by the receive, ticker and test threads at once.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        session.close();
        peer.close();
        if (appender != null) {
            appender.stop();
            root.detachAppender(appender);
        }
    }

    @Test
    void bindListensOnTheLobbyPort() throws Exception {
        int lobbyPort = freePort();
        session.bind(lobbyPort);
        assertNotNull(session.socket(), "the lobby socket must be bound");
        assertEquals(lobbyPort, session.socket().getLocalPort(), "bound on the port it was given");

        sendToLobby(lobbyPort, new GameDatagram(PEER_PLAYER_ID, 0, System.currentTimeMillis()));

        awaitReceived(PEER_PLAYER_ID, 1);
    }

    @Test
    void firstPeerStartsTheCadenceAndDatagramsCarryOurId() throws Exception {
        session.bind(freePort());
        assertFalse(session.isSending(), "no cadence before a peer is registered");
        assertThrows(
                SocketTimeoutException.class,
                this::receiveFromGame,
                "nothing may be sent before a peer is registered");

        session.registerPeer(peerAddress(), PEER_PLAYER_ID);
        assertTrue(session.isSending(), "the first peer starts the cadence");

        GameDatagram first = receiveFromGame();
        GameDatagram second = receiveFromGame();
        assertEquals(OWN_PLAYER_ID, first.senderId(), "datagrams carry this game's own player id");
        assertTrue(
                second.sequence() > first.sequence(),
                "sequences advance: " + first.sequence() + " then " + second.sequence());
    }

    @Test
    void unchangedReregistrationKeepsTheSequenceAdvancing() throws Exception {
        session.bind(freePort());
        session.registerPeer(peerAddress(), PEER_PLAYER_ID);
        GameDatagram before = receiveFromGame();

        // The adapter can repeat a ConnectToPeer; 3.2.2.5 would reset the sequence to zero on a
        // genuine re-registration, which is what this skip exists to avoid.
        session.registerPeer(peerAddress(), PEER_PLAYER_ID);

        GameDatagram after = receiveFromGame();
        assertTrue(
                after.sequence() > before.sequence(),
                "sequence must not restart: " + before.sequence() + " then " + after.sequence());
    }

    @Test
    void closeStopsTheCadenceClosesTheSocketAndEndsTheReceiver() throws Exception {
        session.bind(freePort());
        session.registerPeer(peerAddress(), PEER_PLAYER_ID);
        receiveFromGame();
        Thread receiveThread = session.receiver().receiveThread();
        assertNotNull(receiveThread, "the receive loop must be running before close");

        session.close();

        assertTrue(session.socket().isClosed(), "close must close the shared socket");
        receiveThread.join(LOOP_END_TIMEOUT.toMillis());
        assertFalse(receiveThread.isAlive(), "closing the socket must end the receive loop");
        drain();
        assertThrows(
                SocketTimeoutException.class,
                this::receiveFromGame,
                "no datagram may be sent after close");
    }

    @Test
    void bindFailureLeavesTheSessionInertRatherThanThrowing() throws Exception {
        try (DatagramSocket squatter = new DatagramSocket(0)) {
            session.bind(squatter.getLocalPort());

            assertNull(session.socket(), "a failed bind leaves no socket");
            session.registerPeer(peerAddress(), PEER_PLAYER_ID);
            assertFalse(session.isSending(), "an unbound session sends nothing");
            awaitLog(
                    event -> event.getFormattedMessage().contains("failed to bind lobby port"),
                    "the failed bind must name itself in the log");
        }
    }

    @Test
    void progressLineNamesTheReceivingAndTheSendingPlayer() throws Exception {
        int lobbyPort = freePort();
        session.bind(lobbyPort);
        session.registerPeer(peerAddress(), PEER_PLAYER_ID);

        for (int sequence = 0; sequence < 3; sequence++) {
            sendToLobby(
                    lobbyPort,
                    new GameDatagram(PEER_PLAYER_ID, sequence, System.currentTimeMillis()));
        }

        String expected = "player " + OWN_PLAYER_ID + " peer traffic from player " + PEER_PLAYER_ID;
        awaitLog(
                event -> event.getFormattedMessage().startsWith(expected),
                "the progress line must name both players");
    }

    @Test
    void peerAnnouncedBeforeBindIsDroppedNotThrown() throws Exception {
        session.registerPeer(peerAddress(), PEER_PLAYER_ID);

        assertFalse(session.isSending(), "an unbound session cannot send");
        awaitLog(
                event ->
                        event.getFormattedMessage()
                                .contains("announced before the lobby socket was bound"),
                "the dropped peer must be logged");
        assertThrows(SocketTimeoutException.class, this::receiveFromGame, "and nothing is sent");
    }

    @Test
    void unusablePeerAddressIsDroppedNotThrown() throws Exception {
        session.bind(freePort());

        session.registerPeer("not-an-address", PEER_PLAYER_ID);

        assertFalse(session.isSending(), "a rejected address must not start the cadence");
        awaitLog(
                event -> event.getFormattedMessage().contains("unusable address"),
                "the rejected address must be logged");
    }

    /** The stub peer's address, in the {@code host:port} form the adapter supplies. */
    private String peerAddress() {
        return "127.0.0.1:" + peer.getLocalPort();
    }

    /** A free UDP port, released before the session binds it (benign TOCTOU, as elsewhere). */
    private static int freePort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** Sends one datagram to the game's lobby socket, as the adapter's relay would. */
    private static void sendToLobby(final int lobbyPort, final GameDatagram datagram)
            throws IOException {
        byte[] payload = datagram.toBytes();
        try (DatagramSocket relay = new DatagramSocket()) {
            relay.send(
                    new DatagramPacket(
                            payload,
                            payload.length,
                            new InetSocketAddress("127.0.0.1", lobbyPort)));
        }
    }

    /** Receives one datagram the game sent to the stub peer. */
    private GameDatagram receiveFromGame() throws IOException {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        peer.receive(packet);
        return GameDatagram.parse(packet.getData(), packet.getLength());
    }

    /** Consumes whatever is already queued at the stub, so a later receive sees only new sends. */
    private void drain() throws IOException {
        peer.setSoTimeout((int) QUIET_WINDOW.toMillis());
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        try {
            while (true) {
                peer.receive(new DatagramPacket(buffer, buffer.length));
            }
        } catch (SocketTimeoutException expected) {
            // Nothing left in flight.
        }
    }

    /** Waits until the receiver has attributed {@code count} datagrams to {@code senderId}. */
    private void awaitReceived(final int senderId, final long count) throws InterruptedException {
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (session.receiver().received(senderId) >= count) {
                return;
            }
            Thread.sleep(10);
        }
        fail(
                "receiver saw "
                        + session.receiver().received(senderId)
                        + " datagrams from sender "
                        + senderId
                        + " within "
                        + RECEIVE_TIMEOUT
                        + ", wanted "
                        + count);
    }

    /** Waits for a captured log record matching {@code predicate}, failing with what was seen. */
    private void awaitLog(final Predicate<ILoggingEvent> predicate, final String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + LOG_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ILoggingEvent event : appender.list) {
                if (predicate.test(event)) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        List<String> seen = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            seen.add(event.getFormattedMessage());
        }
        fail(what + " — not seen within " + LOG_TIMEOUT + "; captured: " + seen);
    }
}
