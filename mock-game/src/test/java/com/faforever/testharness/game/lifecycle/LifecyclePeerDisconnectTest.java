package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.game.TestPorts;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The game half of mid-session peer departure (WBS-4.3.4): a {@code DisconnectFromPeer} frame from
 * the adapter ends the game, and ends it as a success.
 *
 * <p>The frames arrive over a real socket from {@link ScriptedGpgNetServer} rather than being
 * posted as events, because the dispatcher registration is half of what is under test. An event
 * posted directly would pass even if no handler were registered for the command at all.
 *
 * <p>{@code PeerDisconnected} already had transitions into ENDED from every non-ENDED state before
 * this card, and nothing posted it. Two of the cases below therefore assert the exit status rather
 * than the state: reaching ENDED was never the missing half, reporting the right outcome on arrival
 * was.
 */
final class LifecyclePeerDisconnectTest {

    /** Budget for a frame to cross the loopback socket and drive the FSM to its target state. */
    private static final long STATE_TIMEOUT_SECONDS = 5;

    /** Budget for one frame the game is expected to emit. */
    private static final long FRAME_TIMEOUT_SECONDS = 1;

    /** PlayerOption frames the game emits per player it configures: Army through Color. */
    private static final int PLAYER_OPTION_FRAMES = 5;

    /** The departing peer's lobby-assigned id, as the adapter names it in the frame. */
    private static final int DEPARTING_PEER_ID = 2;

    private MockGameConfig config;

    private ScriptedGpgNetServer gpgnet;

    /**
     * Stands in for the peer's relay socket inside the ICE adapter. A real bound socket, because
     * since WBS-4.3.2 the game starts sending to whatever a peer frame names and an unroutable
     * destination would log a send failure on every round.
     */
    private DatagramSocket peer;

    /** Every lifecycle a test built, torn down after it so no socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /** Captures the departure log line. Copy-on-write: the reader thread writes while we read. */
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void setup() throws IOException {
        config =
                new MockGameConfig(
                        50000, TestPorts.freeUdpPort(), 1, "Rhiza", 9001, Map.of(), 0, 0);
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void teardown() {
        captured.stop();
        root.detachAppender(captured);
        lifecycles.forEach(lifecycle -> lifecycle.shutdown().run());
        lifecycles.clear();
        peer.close();
        gpgnet.stop();
    }

    @Test
    void departureOfAnAnnouncedPeerEndsTheGameAsASuccess() throws Exception {
        MockGameLifecycle lifecycle = hostingWithOnePeer();

        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of(DEPARTING_PEER_ID)));

        awaitEnded(lifecycle);
        assertEquals(
                MockGameLifecycle.ExitStatus.OK,
                lifecycle.getExitStatus(),
                "an orderly departure is a modelled end, not a failure; reporting FAILED here is"
                        + " what made the surviving client log the exit as a crash");
        assertTrue(
                loggedDeparture(DEPARTING_PEER_ID),
                "the departing id must reach the log, because it is the only record of which peer"
                        + " left: "
                        + messages());
    }

    @Test
    void departureBeforeAnyPeerIsAnnouncedStillEndsTheGame() throws Exception {
        // The transitions cover every non-ENDED state, so a departure needs no peer on record to
        // act on. Worth pinning: the action reads the frame rather than the peers list, and would
        // still work if a later card made the peers list authoritative.
        MockGameLifecycle lifecycle = inLobby();

        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of(DEPARTING_PEER_ID)));

        awaitEnded(lifecycle);
        assertEquals(MockGameLifecycle.ExitStatus.OK, lifecycle.getExitStatus());
    }

    @Test
    void departureDuringALiveMatchAlsoEndsTheGame() throws Exception {
        // LIVE is in the registered set, so the game ends here too. In an orchestrated session the
        // client stops relaying departures once it reaches PLAYING, precisely so this does not
        // happen to a running match, but the two sides move one adapter round trip apart and this
        // transition is what a frame landing inside that window hits. Pinned so the registration
        // cannot be narrowed without a test noticing.
        MockGameLifecycle lifecycle = hostingWithOnePeer();
        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // GameState Launching.
        drainFrames(1);

        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of(DEPARTING_PEER_ID)));

        awaitEnded(lifecycle);
        assertEquals(MockGameLifecycle.ExitStatus.OK, lifecycle.getExitStatus());
        assertTrue(loggedDeparture(DEPARTING_PEER_ID), "the departing id must reach the log");
    }

    @Test
    void malformedDepartureEndsTheGameAsAFailure() throws Exception {
        MockGameLifecycle lifecycle = hostingWithOnePeer();

        // No player id. A frame we could not read is a real generic failure, treated exactly as
        // joinGame and peerConnectionRequest treat theirs, so the status stays FAILED and the
        // process exits non-zero rather than reporting a departure it never actually identified.
        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of()));

        awaitEnded(lifecycle);
        assertEquals(
                MockGameLifecycle.ExitStatus.FAILED,
                lifecycle.getExitStatus(),
                "a frame with no id must not be reported as a clean departure");
    }

    /** A lifecycle in LOBBY, with the connection open and the lobby socket bound. */
    private MockGameLifecycle inLobby() throws Exception {
        MockGameLifecycle lifecycle = lifecycleOn(new GpgNetConnection(gpgnet.port()));
        lifecycle.start();

        gpgnet.start();
        gpgnet.awaitClient();
        // GameState Idle, emitted on connect. Given the longer budget because the lifecycle sleeps
        // GPGNET_CONNECTION_WAIT before its first send, and that sleep starts on the connect thread
        // before this test thread gets here, so how much of it is left to wait out varies.
        drainFrames(1, STATE_TIMEOUT_SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        // GameState Lobby.
        drainFrames(1);
        lifecycle.stateReached(GameState.LOBBY).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return lifecycle;
    }

    /** A lifecycle in HOSTING with one peer announced, the state a lobby-phase departure hits. */
    private MockGameLifecycle hostingWithOnePeer() throws Exception {
        MockGameLifecycle lifecycle = inLobby();

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        drainFrames(PLAYER_OPTION_FRAMES);
        lifecycle.stateReached(GameState.HOSTING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame(
                        "ConnectToPeer",
                        List.of("127.0.0.1:" + peer.getLocalPort(), "Smith", DEPARTING_PEER_ID)));
        drainFrames(PLAYER_OPTION_FRAMES);
        return lifecycle;
    }

    /** Builds a lifecycle on this test's config and records it for teardown. */
    private MockGameLifecycle lifecycleOn(final GpgNetConnection connection) {
        MockGameLifecycle created = new MockGameLifecycle(config, connection, null, null);
        lifecycles.add(created);
        return created;
    }

    /** Bounded wait for the departure to drive the lifecycle into ENDED. */
    private static void awaitEnded(final MockGameLifecycle lifecycle) throws Exception {
        lifecycle.stateReached(GameState.ENDED).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Consumes {@code count} frames the game emitted. {@link ScriptedGpgNetServer#pollReceived}
     * throws an AssertionError on timeout, so a missing frame fails here rather than silently
     * leaving the next assertion reading the wrong one.
     */
    private void drainFrames(final int count) throws InterruptedException {
        drainFrames(count, FRAME_TIMEOUT_SECONDS);
    }

    /**
     * As {@link #drainFrames(int)}, with an explicit per-frame budget for the one drain that has a
     * fixed delay in front of it.
     *
     * @param count how many frames to consume
     * @param timeoutSeconds the budget for each
     * @throws InterruptedException if the wait is interrupted
     */
    private void drainFrames(final int count, final long timeoutSeconds)
            throws InterruptedException {
        for (int i = 0; i < count; i++) {
            gpgnet.pollReceived(timeoutSeconds, TimeUnit.SECONDS);
        }
    }

    /** Whether the departure line was logged for {@code playerId}. */
    private boolean loggedDeparture(final int playerId) {
        return captured.list.stream()
                .anyMatch(
                        event ->
                                event.getFormattedMessage()
                                        .equals(
                                                "Peer (ID: "
                                                        + playerId
                                                        + ") disconnected,"
                                                        + " ending game"));
    }

    /** Every captured message, quoted into a failed assertion. */
    private List<String> messages() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
