package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The game half of mid-session peer departure (WBS-4.3.4, WBS-4.3.6): what a {@code
 * DisconnectFromPeer} frame from the adapter does. In the lobby the game answers it as FA's lobby
 * does and plays on without that peer, whoever left; once the match is live it is ignored; a frame
 * with no usable id ends the game as a failure.
 *
 * <p>The frames arrive over a real socket from {@link ScriptedGpgNetServer} and the game's answers
 * are read back from it, rather than events being posted, because the dispatcher registration and
 * the frames on the wire are half of what is under test. An event posted directly would pass even
 * if no handler were registered for the command at all.
 */
final class LifecyclePeerDisconnectTest {

    /** Budget for a frame to cross the loopback socket and drive the FSM to its target state. */
    private static final long STATE_TIMEOUT_SECONDS = 5;

    /** Budget for one frame the game is expected to emit. */
    private static final long FRAME_TIMEOUT_SECONDS = 1;

    /**
     * How long a frame that must not come is waited for. The game sends its answers inside one
     * transition action, so anything it was going to send has arrived well within this.
     */
    private static final long NO_FRAME_MILLIS = 300;

    /** PlayerOption frames the game emits per player it configures: Army through Color. */
    private static final int PLAYER_OPTION_FRAMES = 5;

    /** The departing peer's lobby-assigned id, as the adapter names it in the frame. */
    private static final int DEPARTING_PEER_ID = 2;

    /** A second peer, announced after {@link #DEPARTING_PEER_ID}. */
    private static final int OTHER_PEER_ID = 3;

    /** An id this game was never told about. */
    private static final int UNKNOWN_PEER_ID = 99;

    /**
     * The auto-launch delay of the one test that needs a launch pending. Long enough that the
     * departure lands before it even on a loaded machine, since the timer starts at HostGame.
     */
    private static final Duration LAUNCH_DELAY = Duration.ofSeconds(5);

    private MockGameConfig config;

    private ScriptedGpgNetServer gpgnet;

    /**
     * Stands in for the peer's relay socket inside the ICE adapter. A real bound socket, because
     * since WBS-4.3.2 the game starts sending to whatever a peer frame names and an unroutable
     * destination would log a send failure on every round.
     */
    private DatagramSocket peer;

    /** The relay socket of {@link #OTHER_PEER_ID}. */
    private DatagramSocket otherPeer;

    /** Every lifecycle a test built, torn down after it so no socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /** Captures the departure log lines. Copy-on-write: the reader thread writes while we read. */
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void setup() throws IOException {
        // Player id 1: as host this game gives itself army, start spot and slot 1, so its first
        // peer gets 2 and the next 3.
        config =
                new MockGameConfig(
                        50000, TestPorts.freeUdpPort(), 1, "Rhiza", 9001, Map.of(), 0, -1, 0, -1);
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);
        otherPeer = new DatagramSocket(0);

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
        otherPeer.close();
        gpgnet.stop();
    }

    @Test
    void theHostKeepsItsLobbyOpenWhenItsOnlyPeerLeaves() throws Exception {
        MockGameLifecycle lifecycle = hostingWithOnePeer(null);

        depart(DEPARTING_PEER_ID);

        // FA's order: DisconnectFromPeer answers first, then the host clears the leaver's slot.
        assertEquals(disconnected(DEPARTING_PEER_ID), nextFrame());
        assertEquals(GpgNetFrame.of("ClearSlot", 2), nextFrame());
        assertEquals(
                GameState.HOSTING,
                lifecycle.getState(),
                "a host whose last peer left keeps its lobby open, as it did before anyone joined");
        assertTrue(
                logged("Peer (ID: 2) disconnected, playing on; 0 peers remain"),
                "the departing id must reach the log: " + messages());
        assertTrue(
                logged("stopped sending peer traffic to player 2"),
                "the traffic to the peer that left must stop: " + messages());
    }

    @Test
    void theHostClearsEachLeaversOwnSlot() throws Exception {
        MockGameLifecycle lifecycle = hostingWithOnePeer(null);
        announce(otherPeer, OTHER_PEER_ID);
        drainFrames(PLAYER_OPTION_FRAMES);

        depart(DEPARTING_PEER_ID);
        assertEquals(disconnected(DEPARTING_PEER_ID), nextFrame());
        assertEquals(GpgNetFrame.of("ClearSlot", 2), nextFrame());

        depart(OTHER_PEER_ID);
        assertEquals(disconnected(OTHER_PEER_ID), nextFrame());
        assertEquals(
                GpgNetFrame.of("ClearSlot", 3),
                nextFrame(),
                "the slot is the one this peer was given, not a count of the peers still here");
        assertEquals(GameState.HOSTING, lifecycle.getState());
    }

    @Test
    void aJoinerWhoseHostLeavesStaysInItsLobby() throws Exception {
        // faf-server ends its own game first when a host leaves normally, so nobody is told, but a
        // host it aborts directly (a kick, say) is announced. FA's joiner then drops the host and
        // waits behind "Connection to host timed out"; this game drops it and waits too.
        MockGameLifecycle lifecycle = joining(DEPARTING_PEER_ID);

        depart(DEPARTING_PEER_ID);

        assertEquals(disconnected(DEPARTING_PEER_ID), nextFrame());
        assertNoFrame("a joiner sends no PlayerOption, so it has no slot to clear");
        assertEquals(GameState.JOINING, lifecycle.getState());
    }

    @Test
    void aJoinerPlaysOnWhenAnotherJoinerLeaves() throws Exception {
        MockGameLifecycle lifecycle = joining(DEPARTING_PEER_ID);
        announce(otherPeer, OTHER_PEER_ID);

        depart(OTHER_PEER_ID);

        assertEquals(disconnected(OTHER_PEER_ID), nextFrame());
        assertNoFrame("a joiner has no slot to clear");
        assertEquals(GameState.JOINING, lifecycle.getState());
        assertTrue(
                logged("Peer (ID: 3) disconnected, playing on; 1 peers remain"),
                "the joiner still has its host: " + messages());
    }

    @Test
    void aNoticeAboutAPeerTheGameNeverHadIsAnsweredAndChangesNothing() throws Exception {
        // disconnect_all_peers() notifies every connection in the game, so a joiner that aborts
        // before it finishes joining is announced to a host never told to connect to it. FA's
        // DisconnectFromPeer answers any id.
        MockGameLifecycle lifecycle = hostingWithOnePeer(null);

        depart(UNKNOWN_PEER_ID);

        assertEquals(disconnected(UNKNOWN_PEER_ID), nextFrame());
        assertNoFrame("no slot is cleared for a peer that never had one");
        assertEquals(GameState.HOSTING, lifecycle.getState());
        assertTrue(
                logged("Peer (ID: 99) disconnected but never joined this game; ignoring"),
                "the ignored notice must reach the log: " + messages());

        // The peer this host did have is still here, so its own departure still clears its slot.
        depart(DEPARTING_PEER_ID);
        assertEquals(disconnected(DEPARTING_PEER_ID), nextFrame());
        assertEquals(GpgNetFrame.of("ClearSlot", 2), nextFrame());
    }

    @Test
    void aDepartureBeforeARoleIsAnsweredAndChangesNothing() throws Exception {
        // LOBBY is after CreateLobby and before HostGame or JoinGame: FA has a lobby that answers,
        // and this game has no peer to drop. Nothing sends one here in practice.
        MockGameLifecycle lifecycle = inLobby(null);

        depart(DEPARTING_PEER_ID);

        assertEquals(disconnected(DEPARTING_PEER_ID), nextFrame());
        assertNoFrame("nothing else answers it");
        assertEquals(GameState.LOBBY, lifecycle.getState());
    }

    @Test
    void aDepartureDuringALiveMatchIsIgnored() throws Exception {
        // Reachable in one narrow window: the game is LIVE from its GameState Launching, and its
        // client stops relaying departures only once that frame has come back to it. FA has
        // destroyed its lobby by then, and a running game processes no GPGNet input.
        MockGameLifecycle lifecycle = hostingWithOnePeer(null);
        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // GameState Launching.
        drainFrames(1);

        depart(DEPARTING_PEER_ID);

        awaitLogged("ignoring DisconnectFromPeer during a live match: [2]");
        assertNoFrame("a running game answers nothing");
        assertEquals(GameState.LIVE, lifecycle.getState());
    }

    @Test
    void aDepartureDoesNotCancelAPendingLaunch() throws Exception {
        // A departure is a self-loop, which keeps every pending FSM timeout armed, and the launch
        // timer is on the lifecycle's own scheduler, which no transition touches. Pinned for #498,
        // which moves that timer onto an FSM timeout.
        MockGameLifecycle lifecycle = hostingWithOnePeer(LAUNCH_DELAY);
        CompletableFuture<Void> live = lifecycle.stateReached(GameState.LIVE);

        depart(DEPARTING_PEER_ID);
        assertEquals(disconnected(DEPARTING_PEER_ID), nextFrame());
        assertEquals(GpgNetFrame.of("ClearSlot", 2), nextFrame());

        live.get(LAUNCH_DELAY.toSeconds() + STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(GpgNetFrame.of("GameState", "Launching"), nextFrame());
    }

    @Test
    void malformedDepartureEndsTheGameAsAFailure() throws Exception {
        MockGameLifecycle lifecycle = hostingWithOnePeer(null);

        // No player id. A frame we could not read is a real generic failure, treated exactly as
        // joinGame and peerConnectionRequest treat theirs, so the status stays FAILED and the
        // process exits non-zero rather than reporting a departure it never actually identified.
        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of()));

        lifecycle.stateReached(GameState.ENDED).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.FAILED,
                lifecycle.getExitStatus(),
                "a frame with no id must not be reported as a clean departure");
    }

    /**
     * A lifecycle in LOBBY, with the connection open and the lobby socket bound.
     *
     * @param launchDelay the auto-launch delay, or {@code null} for none
     */
    private MockGameLifecycle inLobby(final Duration launchDelay) throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        config, new GpgNetConnection(gpgnet.port()), launchDelay, null);
        lifecycles.add(lifecycle);
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

    /**
     * A lifecycle in HOSTING with one peer announced, the state a lobby-phase departure hits.
     *
     * @param launchDelay the auto-launch delay, or {@code null} for none
     */
    private MockGameLifecycle hostingWithOnePeer(final Duration launchDelay) throws Exception {
        MockGameLifecycle lifecycle = inLobby(launchDelay);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        drainFrames(PLAYER_OPTION_FRAMES);
        lifecycle.stateReached(GameState.HOSTING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        announce(peer, DEPARTING_PEER_ID);
        drainFrames(PLAYER_OPTION_FRAMES);
        return lifecycle;
    }

    /**
     * A lifecycle in JOINING, joined to the host {@code hostId}, whose relay is {@link #peer}.
     *
     * @param hostId the host's player id
     */
    private MockGameLifecycle joining(final int hostId) throws Exception {
        MockGameLifecycle lifecycle = inLobby(null);

        gpgnet.sendFrame(
                new GpgNetFrame(
                        "JoinGame", List.of("127.0.0.1:" + peer.getLocalPort(), "Hosty", hostId)));
        lifecycle.stateReached(GameState.JOINING).get(STATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return lifecycle;
    }

    /** The adapter announcing a peer whose relay is {@code relay}. */
    private void announce(final DatagramSocket relay, final int playerId) throws IOException {
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "ConnectToPeer",
                        List.of(
                                "127.0.0.1:" + relay.getLocalPort(),
                                "Smith" + playerId,
                                playerId)));
    }

    /** The adapter forwarding faf-server's notice that {@code playerId} left. */
    private void depart(final int playerId) throws IOException {
        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of(playerId)));
    }

    /** The frame FA's lobby answers a departure with: the id as a string chunk. */
    private static GpgNetFrame disconnected(final int playerId) {
        return GpgNetFrame.of("Disconnected", Integer.toString(playerId));
    }

    /** The next frame the game emitted, failing after {@link #FRAME_TIMEOUT_SECONDS}. */
    private GpgNetFrame nextFrame() throws InterruptedException {
        return gpgnet.pollReceived(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Asserts the game emits nothing more within {@link #NO_FRAME_MILLIS}. */
    private void assertNoFrame(final String why) {
        assertThrows(
                AssertionError.class,
                () -> gpgnet.pollReceived(NO_FRAME_MILLIS, TimeUnit.MILLISECONDS),
                why);
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

    /** Whether {@code line} was logged verbatim. */
    private boolean logged(final String line) {
        return captured.list.stream().anyMatch(event -> event.getFormattedMessage().equals(line));
    }

    /** Bounded wait for {@code line}, which the reader thread logs. */
    private void awaitLogged(final String line) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STATE_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (logged(line)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("'" + line + "' was not logged within " + STATE_TIMEOUT_SECONDS + " s: " + messages());
    }

    /** Every captured message, quoted into a failed assertion. */
    private List<String> messages() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
