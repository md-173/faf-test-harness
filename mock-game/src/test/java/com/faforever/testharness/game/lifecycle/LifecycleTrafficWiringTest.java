package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.game.TestPorts;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import com.faforever.testharness.game.net.GameDatagram;
import com.faforever.testharness.game.net.GameUdpSender;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle's half of peer traffic (WBS-4.3.2), black-box: real GPGNet frames in over {@link
 * ScriptedGpgNetServer}, real datagrams out to a plain socket standing in for the ICE adapter's
 * per-peer relay. The traffic session's own internals are covered by {@code
 * GameTrafficSessionTest}; nothing here reaches past the lifecycle's public surface.
 *
 * <p>That teardown closed the lobby socket is asserted by re-binding the port, which is what a
 * caller can actually observe and does not depend on package-private accessors from another
 * package.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class LifecycleTrafficWiringTest {

    /** This game's player id — the {@code senderId} every datagram it sends must carry. */
    private static final int OWN_PLAYER_ID = 11;

    /** The peer's player id, as the lobby would have assigned it. */
    private static final int PEER_PLAYER_ID = 22;

    /** Budget for a state the FSM should reach almost immediately. */
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(5);

    /** Budget for a datagram to make the loopback trip at the production cadence. */
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);

    /** Budget for a progress line, which is sampled once a second in production. */
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(15);

    /** How long "nothing more arrives" is observed for, after teardown. */
    private static final Duration QUIET_WINDOW = Duration.ofMillis(500);

    /** Drop records awaited before silence at the stub counts as evidence of dropping. */
    private static final int MIN_DROPS = 3;

    /** Receive buffer for the stub peer, comfortably above one {@link GameDatagram}. */
    private static final int RECEIVE_BUFFER_BYTES = 256;

    /** This test's config, with its own free lobby port. */
    private MockGameConfig config;

    /** The scripted adapter the lifecycle talks GPGNet to. */
    private ScriptedGpgNetServer gpgnet;

    /** Stands in for the peer's relay socket inside the adapter: where the game's traffic goes. */
    private DatagramSocket peer;

    /** The lifecycle under test. */
    private MockGameLifecycle lifecycle;

    /** Root logger the appender is attached to. */
    private Logger root;

    /** Captures the progress line. */
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);
        peer.setSoTimeout((int) RECEIVE_TIMEOUT.toMillis());

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // The receive and ticker threads log while this is attached.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);

        lifecycle = newLifecycle(0);
    }

    /**
     * Builds the lifecycle under test, and the config it reads, with its own free lobby port.
     *
     * @param udpDropPercent the config's drop percentage; {@code 0} everywhere except the test that
     *     proves the value reaches the sender
     * @return a lifecycle that is constructed but not started
     */
    private MockGameLifecycle newLifecycle(final int udpDropPercent) throws IOException {
        config =
                new MockGameConfig(
                        50000,
                        TestPorts.freeUdpPort(),
                        OWN_PLAYER_ID,
                        "Rhiza",
                        9001,
                        Map.of(),
                        0,
                        udpDropPercent);
        return new MockGameLifecycle(config, new GpgNetConnection(gpgnet.port()), null, null);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutdown().run();
        peer.close();
        gpgnet.stop();
        appender.stop();
        root.detachAppender(appender);
    }

    @Test
    void createLobbyBindsTheLobbySocketAndTheGameReportsWhatItReceives() throws Exception {
        reachLobby();

        // The adapter's return leg: a peer's datagram, repackaged to the game's lobby port.
        sendToLobby(new GameDatagram(PEER_PLAYER_ID, 0, System.currentTimeMillis()));
        sendToLobby(new GameDatagram(PEER_PLAYER_ID, 1, System.currentTimeMillis()));

        awaitLog(
                event -> event.getFormattedMessage().startsWith(progressPrefix()),
                "the game must report the traffic it received, naming both players");
    }

    @Test
    void bindsThePortCreateLobbyNamesRatherThanTheLaunchArgument() throws Exception {
        // The adapter fills CreateLobby from GPGNetServer.getLobbyPort() and relays every inbound
        // peer datagram to that same port, so the frame decides where traffic physically arrives.
        // A game that bound its --lobby-port instead would run, look healthy, and hear nothing.
        int announced = TestPorts.freeUdpPort();
        assertTrue(
                announced != config.lobbyPort(),
                "the two ports must differ for this to prove anything");

        gpgnet.start();
        // Since WBS-3.2.4.1-fix (#262) construction is inert, so the connect needs start().
        lifecycle.start();
        gpgnet.awaitClient();
        gpgnet.pollReceived(1, TimeUnit.SECONDS); // GameState Idle
        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, announced, "Rhiza", OWN_PLAYER_ID, 1)));
        awaitState(GameState.LOBBY);

        sendToPort(announced, new GameDatagram(PEER_PLAYER_ID, 0, System.currentTimeMillis()));
        sendToPort(announced, new GameDatagram(PEER_PLAYER_ID, 1, System.currentTimeMillis()));

        awaitLog(
                event -> event.getFormattedMessage().startsWith(progressPrefix()),
                "the game must receive on the port CreateLobby named, not on --lobby-port");
    }

    @Test
    void connectToPeerStartsTrafficToThatPeerOnTheHostPath() throws Exception {
        reachLobby();
        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        awaitState(GameState.HOSTING);

        gpgnet.sendFrame(
                new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", PEER_PLAYER_ID)));

        GameDatagram first = receiveFromGame();
        GameDatagram second = receiveFromGame();
        assertEquals(OWN_PLAYER_ID, first.senderId(), "traffic carries this game's own player id");
        assertTrue(
                second.sequence() > first.sequence(),
                "sequences advance: " + first.sequence() + " then " + second.sequence());
    }

    /**
     * The drop percentage on {@link MockGameConfig} must reach the sender the lifecycle builds
     * (#353). It once stopped at the config record, so the flag parsed, validated and did nothing.
     *
     * <p>100 rather than a partial value because it is deterministic: every round is dropped, so
     * there is no statistics to tolerate and nothing still in flight. The positive control, the
     * same path at the default of 0, is {@link #connectToPeerStartsTrafficToThatPeerOnTheHostPath}.
     *
     * <p>Silence at the stub alone could also mean the cadence never started. So the sender's own
     * drop records are awaited first: they prove rounds ran and were suppressed, and only then is
     * the silence evidence.
     */
    @Test
    void configuredDropPercentReachesTheSenderTheLifecycleBuilds() throws Exception {
        // Replaces setUp's lifecycle, which was never started, so shutting it down is inert.
        lifecycle.shutdown().run();
        lifecycle = newLifecycle(100);

        // The drop record is DEBUG and the harness defaults to INFO. Restores the logger's own
        // level, normally null (inherited), not its effective one, which would pin INFO on it.
        Logger senderLogger =
                ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(GameUdpSender.class);
        Level previous = senderLogger.getLevel();
        senderLogger.setLevel(Level.DEBUG);
        try {
            reachLobby();
            gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
            awaitState(GameState.HOSTING);
            gpgnet.sendFrame(
                    new GpgNetFrame(
                            "ConnectToPeer", List.of(peerAddress(), "Smith", PEER_PLAYER_ID)));

            awaitDropsToPeer(MIN_DROPS);
        } finally {
            senderLogger.setLevel(previous);
        }

        peer.setSoTimeout((int) QUIET_WINDOW.toMillis());
        assertThrows(
                SocketTimeoutException.class,
                this::receiveFromGame,
                "at a drop percentage of 100 no datagram may reach the peer");
    }

    @Test
    void joinGameStartsTrafficToTheHostOnTheJoinerPath() throws Exception {
        reachLobby();

        gpgnet.sendFrame(
                new GpgNetFrame("JoinGame", List.of(peerAddress(), "Smith", PEER_PLAYER_ID)));
        awaitState(GameState.JOINING);

        assertEquals(
                OWN_PLAYER_ID,
                receiveFromGame().senderId(),
                "a joiner sends to the host it was told to join");
    }

    @Test
    void endedStopsTheTrafficAndReleasesTheLobbyPort() throws Exception {
        reachLobby();
        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        awaitState(GameState.HOSTING);
        gpgnet.sendFrame(
                new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", PEER_PLAYER_ID)));
        receiveFromGame();

        lifecycle.launchMatch();
        awaitState(GameState.LIVE);
        lifecycle.endMatch();
        awaitState(GameState.ENDED);

        assertNoMoreTraffic();
        assertLobbyPortReleased();
    }

    @Test
    void shutdownOutOfBandStopsTheTrafficWithoutReachingEnded() throws Exception {
        reachLobby();
        gpgnet.sendFrame(
                new GpgNetFrame("JoinGame", List.of(peerAddress(), "Smith", PEER_PLAYER_ID)));
        awaitState(GameState.JOINING);
        receiveFromGame();

        // The SIGTERM path: the sequence runs on its own, and the FSM never reaches ENDED.
        lifecycle.shutdown().run();

        assertEquals(
                GameState.JOINING, lifecycle.getState(), "an out-of-band teardown posts no event");
        assertNoMoreTraffic();
        assertLobbyPortReleased();
    }

    /** Drives the FSM to LOBBY, which is where the lobby socket binds. */
    private void reachLobby() throws Exception {
        gpgnet.start();
        // Since WBS-3.2.4.1-fix (#262) construction is inert, so the connect needs start().
        lifecycle.start();
        gpgnet.awaitClient();
        gpgnet.pollReceived(1, TimeUnit.SECONDS); // GameState Idle
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", OWN_PLAYER_ID, 1)));
        awaitState(GameState.LOBBY);
        gpgnet.pollReceived(1, TimeUnit.SECONDS); // GameState Lobby
    }

    /** Waits for a state, failing with the budget rather than hanging. */
    private void awaitState(final GameState state) throws Exception {
        lifecycle.stateReached(state).get(STATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The stub peer's address, in the {@code host:port} form the adapter supplies. */
    private String peerAddress() {
        return "127.0.0.1:" + peer.getLocalPort();
    }

    /** The start of the progress line this game must log for the peer's traffic. */
    private String progressPrefix() {
        return "player " + OWN_PLAYER_ID + " peer traffic from player " + PEER_PLAYER_ID;
    }

    /** Sends one datagram to the game's lobby port, as the adapter's relay would. */
    private void sendToLobby(final GameDatagram datagram) throws IOException {
        sendToPort(config.lobbyPort(), datagram);
    }

    /** Sends one datagram to an explicit port, for the test that pins which port is bound. */
    private void sendToPort(final int port, final GameDatagram datagram) throws IOException {
        byte[] payload = datagram.toBytes();
        try (DatagramSocket relay = new DatagramSocket()) {
            relay.send(
                    new DatagramPacket(
                            payload, payload.length, new InetSocketAddress("127.0.0.1", port)));
        }
    }

    /** Receives one datagram the game sent to the stub peer. */
    private GameDatagram receiveFromGame() throws IOException {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        peer.receive(packet);
        return GameDatagram.parse(packet.getData(), packet.getLength());
    }

    /** Drains what is already in flight, then asserts the cadence has genuinely stopped. */
    private void assertNoMoreTraffic() throws IOException, InterruptedException {
        peer.setSoTimeout((int) QUIET_WINDOW.toMillis());
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        try {
            while (true) {
                peer.receive(new DatagramPacket(buffer, buffer.length));
            }
        } catch (SocketTimeoutException expected) {
            // Nothing left in flight; anything after this would be a live cadence.
        }
        assertThrows(
                SocketTimeoutException.class,
                this::receiveFromGame,
                "no datagram may be sent once the game has been torn down");
        assertCadenceStopped();
    }

    /**
     * Asserts the cadence itself stopped, not merely that its socket closed.
     *
     * <p>Silence at the stub proves nothing on its own — teardown closes the socket, so nothing
     * arrives either way. A cadence that survived teardown keeps firing into that closed socket and
     * logs a send failure every round, so any growth at all is the tell.
     *
     * <p><b>Where this is called from matters.</b> Stopping does not join a round already in
     * flight, so one straggler failure can land just after teardown and this comparison would fail
     * on it. It is safe only because the caller has already spent a drain plus a quiet window on
     * the socket first. Called immediately after teardown, it would flake.
     */
    private void assertCadenceStopped() throws InterruptedException {
        long before = sendFailures();
        Thread.sleep(QUIET_WINDOW.toMillis());
        long after = sendFailures();
        if (after > before) {
            fail(
                    "the send cadence outlived teardown: send failures went from "
                            + before
                            + " to "
                            + after
                            + " over "
                            + QUIET_WINDOW);
        }
    }

    /**
     * Captured send failures, which only a live cadence on a closed socket can produce.
     *
     * <p>Matched on the logger and level rather than the message text. {@code GameUdpSender} emits
     * exactly one WARN — the per-round send failure — and {@code GameUdpSenderTest} pins that a
     * failed send logs at WARN from that class, so this anchor holds even if the wording changes.
     * Matching the phrase instead would let a reword turn this detector into a permanent pass, the
     * exact failure it exists to prevent.
     */
    private long sendFailures() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> GameUdpSender.class.getName().equals(event.getLoggerName()))
                .count();
    }

    /**
     * Waits for at least {@code count} captured drop records addressed to the stub peer.
     *
     * <p>Matched on the logger, the level and the peer id argument rather than the message text,
     * for the same reason as {@link #sendFailures()}. The drop record is {@code GameUdpSender}'s
     * only DEBUG line, and its second argument is the peer id; a substring match on the id could
     * also hit a sequence number or a port.
     */
    private void awaitDropsToPeer(final int count) throws InterruptedException {
        Predicate<ILoggingEvent> dropToPeer =
                event ->
                        event.getLevel() == Level.DEBUG
                                && GameUdpSender.class.getName().equals(event.getLoggerName())
                                && event.getArgumentArray() != null
                                && event.getArgumentArray().length > 1
                                && Integer.valueOf(PEER_PLAYER_ID)
                                        .equals(event.getArgumentArray()[1]);
        long deadline = System.nanoTime() + LOG_TIMEOUT.toNanos();
        long seen = 0;
        while (System.nanoTime() < deadline) {
            seen = appender.list.stream().filter(dropToPeer).count();
            if (seen >= count) {
                return;
            }
            Thread.sleep(20);
        }
        fail(
                "expected at least "
                        + count
                        + " drop records to peer "
                        + PEER_PLAYER_ID
                        + " within "
                        + LOG_TIMEOUT
                        + ", saw "
                        + seen
                        + "; the configured drop percentage did not reach the sender");
    }

    /**
     * Re-binding the lobby port proves the shared socket was closed.
     *
     * <p>The mirror of the TOCTOU window in {@link TestPorts#freeUdpPort()}: between teardown
     * releasing the port and this re-bind, something else on the machine can take it, and the
     * assertion then fails despite the release having worked. Left alone rather than retried —
     * losing that race surfaces as a bind failure here, which is a visibly flaky test rather than a
     * silent pass, and a retry loop would weaken what the assertion actually pins.
     */
    private void assertLobbyPortReleased() throws IOException {
        try (DatagramSocket rebound = new DatagramSocket(config.lobbyPort())) {
            assertTrue(rebound.isBound(), "the lobby port must be free once the game is torn down");
        }
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
            Thread.sleep(20);
        }
        List<String> seen = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            seen.add(event.getFormattedMessage());
        }
        fail(what + " — not seen within " + LOG_TIMEOUT + "; captured: " + seen);
    }
}
