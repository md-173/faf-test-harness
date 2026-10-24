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
import com.faforever.testharness.game.config.Seat;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import com.faforever.testharness.game.net.GameDatagram;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
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
 * The peer frames of FA's matchmaker lobby (WBS-3.2.4.1-fix, #506), its host's {@code PlayerOption}
 * frames, its {@code LaunchStatus} reports and its launch once {@code Ready} (WBS-3.2.4.3-fix,
 * #534). FA loads {@code autolobby.lua} rather than the custom {@code lobby.lua} when {@code
 * CreateLobby}'s init mode is 1, which the adapter sends for a matchmaker game, and the two answer
 * peer frames differently. The custom lobby's are pinned by {@link LifecyclePeerConnectTest} and
 * {@link LifecyclePeerDisconnectTest}.
 *
 * <p>As there, frames arrive over a real socket from {@link ScriptedGpgNetServer} and the game's
 * answers are read back from it, so the dispatcher registration is under test too. A link comes up
 * the way it does in a session: a stub peer sends real {@link GameDatagram}s, carrying that peer's
 * seat, to the game's lobby port. The receiver reports a sender once, so a datagram is only ever
 * sent once its peer is registered, or the report would be spent before there was anyone to report.
 * A matchmaker host answers {@code ConnectToPeer} with nothing, so {@link #announce} waits for the
 * line the game logs inside that action instead.
 *
 * <p>A matchmaker game also reports its {@code LaunchStatus} every 2 s, so a frame can land either
 * side of one wherever a test is slow. {@link #nextFrame} and {@link #assertNoFrame} pass over
 * those reports, and the tests about them read every frame in order with {@link #nextRawFrame}.
 */
final class LifecycleMatchmakerLobbyTest {

    /** {@code CreateLobby}'s init mode for a matchmaker game: the adapter's {@code AUTO}. */
    private static final int MATCHMAKER_INIT_MODE = 1;

    /** {@code CreateLobby}'s init mode for a custom game: the adapter's {@code NORMAL}. */
    private static final int CUSTOM_INIT_MODE = 0;

    /** Budget for a frame, a log line or a state the game is expected to reach. */
    private static final long TIMEOUT_SECONDS = 5;

    /**
     * How long a frame that must not come is waited for. The game sends its answers inside one
     * transition action, so anything it was going to send has arrived well within this.
     */
    private static final long NO_FRAME_MILLIS = 300;

    /** Budget for a periodic {@code LaunchStatus}: one 2 s period, with room for a loaded box. */
    private static final long STATUS_TIMEOUT_SECONDS = 10;

    /** How long a launch that must not come is waited for, with a launch delay of zero. */
    private static final long NO_LAUNCH_MILLIS = 500;

    /** How long after {@code GameState Launching} no report may come: past one 2 s period. */
    private static final long AFTER_LAUNCH_MILLIS = 2500;

    /** How long the period test counts reports for, from before it sends {@code HostGame}. */
    private static final long PERIOD_WINDOW_MILLIS = 4500;

    /**
     * The most periodic reports that fit in {@link #PERIOD_WINDOW_MILLIS} at FA's 2 s period: one
     * on {@code HostGame}, then 2 s and 4 s later. Each is sent at least 2 s after the one before,
     * and a loaded machine can only delay one, so this bound cannot fail spuriously.
     */
    private static final int MAX_STATUSES_IN_WINDOW = 3;

    /** PlayerOption frames a custom host emits per player it configures: Army through Color. */
    private static final int CUSTOM_PLAYER_OPTION_FRAMES = 5;

    /** PlayerOption frames a matchmaker host emits per seated player: Team through Faction. */
    private static final int MATCHMAKER_PLAYER_OPTION_FRAMES = 4;

    /** This game's player id. */
    private static final int OWN_ID = 1;

    /** The first peer a host is told about, and the host a joiner joins. */
    private static final int PEER_ID = 2;

    /** A second peer. */
    private static final int OTHER_PEER_ID = 3;

    /** An id this game was never told about. */
    private static final int UNKNOWN_PEER_ID = 99;

    /**
     * This game's seat. Its start spot lies between the two peers', so a host that arrived first is
     * army 2, and every field differs from the army and from each other, so one sent as another
     * cannot pass.
     */
    private static final Seat OWN_SEAT = new Seat(3, 5, 4);

    /** {@link #PEER_ID}'s seat, which its datagrams carry: army 1 by start spot. */
    private static final Seat PEER_SEAT = new Seat(2, 3, 4);

    /** {@link #OTHER_PEER_ID}'s seat, which its datagrams carry: army 3 by start spot. */
    private static final Seat OTHER_SEAT = new Seat(2, 7, 1);

    /** A game option, to show which lobby sends it. */
    private static final Map<String, String> GAME_OPTION = Map.of("Victory", "demoralization");

    private MockGameConfig config;

    /**
     * The launch delay the lifecycle is built with: {@code null}, never launching on its own, but
     * for the tests of the launch on {@code Ready}.
     */
    private Duration launchDelay;

    private ScriptedGpgNetServer gpgnet;

    /**
     * Stands in for {@link #PEER_ID}'s relay socket inside the ICE adapter. A real bound socket,
     * because the game sends its traffic to whatever a peer frame names.
     */
    private DatagramSocket peer;

    /** The relay socket of {@link #OTHER_PEER_ID}. */
    private DatagramSocket otherPeer;

    /** Every lifecycle a test built, torn down after it so no socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /** Captures the game's log lines. Copy-on-write: other threads write while we read. */
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void setup() throws IOException {
        config = newConfig(Map.of());
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
    void aHostSendsNoPlayerOptionOrGameOptionOnHostGame() throws Exception {
        config = newConfig(GAME_OPTION);

        MockGameLifecycle lifecycle = hosting();

        // FA's matchmaker host seats itself and reports nobody until a joiner is seated, and it
        // sends no GameOption (AutolobbyController.lua). The skipped option is logged instead.
        awaitLogged("not sending 1 --game-option");
        assertNoFrame("the matchmaker lobby sends only its LaunchStatus on HostGame");
        assertEquals(GameState.HOSTING, lifecycle.getState());
    }

    @Test
    void aHostReportsHostingThenMissingLocalPeers() throws Exception {
        hosting();

        // FA's host reports Hosting as it starts hosting, and its 2 s reports start there too,
        // with no peer linked yet. FA's Lua does not fix which of the two comes first.
        assertEquals(launchStatus("Hosting"), nextRawFrame());
        assertEquals(launchStatus("Missing local peers"), nextRawFrame());
    }

    @Test
    void aHostReportsReadyOnceItsJoinerIsLinkedAndStaysReadyAfterItLeaves() throws Exception {
        hosting();
        announce(peer, PEER_ID);
        sendDatagram(PEER_ID, 0);
        assertEquals(established(PEER_ID), nextFrame());
        drainFrames(2 * MATCHMAKER_PLAYER_OPTION_FRAMES);

        // Of the 2 players expected, the one other than the host is linked.
        assertEquals("Ready", nextStatus());

        depart(PEER_ID);
        assertEquals(GpgNetFrame.of("DisconnectedPeer", Integer.toString(PEER_ID)), nextFrame());

        // FA never forgets a linked peer (ConnectionMatrix), so a departure leaves it Ready.
        assertEquals("Ready", nextStatus());
    }

    @Test
    void launchStatusRepeatsEveryTwoSecondsAndNoFaster() throws Exception {
        inLobby();

        // The window opens before HostGame, so every report counted was sent inside it. A report
        // read late is not counted, which can only lower the count.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PERIOD_WINDOW_MILLIS);
        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        int statuses = 0;
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            GpgNetFrame frame = pollOrNull(left);
            if (frame != null) {
                assertEquals("LaunchStatus", frame.command(), "only a LaunchStatus is due");
                if (!frame.stringArg(0).equals("Hosting")) {
                    statuses++;
                }
            }
        }

        assertTrue(
                statuses <= MAX_STATUSES_IN_WINDOW,
                statuses + " periodic LaunchStatus frames in " + PERIOD_WINDOW_MILLIS + " ms");
    }

    @Test
    void noLaunchStatusFollowsTheLaunch() throws Exception {
        MockGameLifecycle lifecycle = hosting();

        lifecycle.launchMatch();
        assertPlayerOptions(OWN_ID, OWN_SEAT, 1);
        assertEquals(GpgNetFrame.of("GameState", "Launching"), nextFrame());

        // FA's reports end with its lobby at launch. One still armed would come within a period.
        assertNoRawFrameFor(AFTER_LAUNCH_MILLIS, "no LaunchStatus once the match is live");
    }

    @Test
    void aHostLaunchesOnlyOnceItHasReportedReady() throws Exception {
        launchDelay = Duration.ZERO;
        hosting();

        // With no delay, a launch armed on HostGame would come at once.
        assertNoFrameFor(NO_LAUNCH_MILLIS, "no launch before the joiner is linked");

        announce(peer, PEER_ID);
        sendDatagram(PEER_ID, 0);
        assertEquals(established(PEER_ID), nextFrame());
        drainFrames(2 * MATCHMAKER_PLAYER_OPTION_FRAMES);

        // Ready first, then the launch it arms: the set again, then Launching.
        assertEquals("Ready", nextStatus());
        assertPlayerOptions(PEER_ID, PEER_SEAT, 1);
        assertPlayerOptions(OWN_ID, OWN_SEAT, 2);
        assertEquals(GpgNetFrame.of("GameState", "Launching"), nextFrame());
    }

    @Test
    void aCustomHostStillSendsItsGameOptions() throws Exception {
        config = newConfig(GAME_OPTION);

        hosting(CUSTOM_INIT_MODE);

        assertEquals(GpgNetFrame.of("GameOption", "Victory", "demoralization"), nextFrame());
    }

    @Test
    void aHostAnswersConnectToPeerWithNothing() throws Exception {
        MockGameLifecycle lifecycle = hosting();

        announce(peer, PEER_ID);

        // Not the custom lobby's Disconnected and PlayerOption set: the matchmaker lobby's
        // ConnectToPeer only connects (autolobby.lua), and its host reports a joiner once the
        // joiner's AddPlayer arrives over the link.
        assertNoFrame("the matchmaker lobby answers ConnectToPeer with nothing");
        assertEquals(GameState.HOSTING, lifecycle.getState());
    }

    @Test
    void aHostSeatsEachJoinerFromItsDatagramAndReportsEverySeatedPlayer() throws Exception {
        hosting();
        announce(peer, PEER_ID);

        sendDatagram(PEER_ID, 0);

        // The link report, then FA's set for every seated player in start-spot order: the joiner
        // at 3 is army 1 and the host at 5 army 2, though the host was seated first.
        assertEquals(established(PEER_ID), nextFrame());
        assertPlayerOptions(PEER_ID, PEER_SEAT, 1);
        assertPlayerOptions(OWN_ID, OWN_SEAT, 2);

        announce(otherPeer, OTHER_PEER_ID);
        sendDatagram(OTHER_PEER_ID, 0);

        assertEquals(established(OTHER_PEER_ID), nextFrame());
        assertPlayerOptions(PEER_ID, PEER_SEAT, 1);
        assertPlayerOptions(OWN_ID, OWN_SEAT, 2);
        assertPlayerOptions(OTHER_PEER_ID, OTHER_SEAT, 3);
        assertNoFrame("no Color, and nothing else, follows the set");
    }

    @Test
    void aHostReportsEverySeatedPlayerAgainBeforeLaunch() throws Exception {
        MockGameLifecycle lifecycle = hosting();
        announce(peer, PEER_ID);
        sendDatagram(PEER_ID, 0);
        drainFrames(1 + 2 * MATCHMAKER_PLAYER_OPTION_FRAMES);

        lifecycle.launchMatch();

        // FA's LaunchThread sends the set once more, then the game launches.
        assertPlayerOptions(PEER_ID, PEER_SEAT, 1);
        assertPlayerOptions(OWN_ID, OWN_SEAT, 2);
        assertEquals(GpgNetFrame.of("GameState", "Launching"), nextFrame());
    }

    @Test
    void aJoinerSendsNoPeerFrameOnJoinGameOrConnectToPeer() throws Exception {
        MockGameLifecycle lifecycle = joining();

        announce(otherPeer, OTHER_PEER_ID);

        assertNoFrame("the matchmaker lobby answers neither JoinGame nor ConnectToPeer");
        assertEquals(GameState.JOINING, lifecycle.getState());
    }

    @Test
    void aJoinerReportsConnectingAndStartsItsReportsOnItsHostsLink() throws Exception {
        config = newConfig(Map.of(), 3);
        joining();
        assertEquals(launchStatus("Connecting"), nextRawFrame());

        // Another joiner's link is reported, but FA's joiner starts its 2 s reports only once its
        // connection to the host is up (ConnectionToHostEstablished).
        announce(otherPeer, OTHER_PEER_ID);
        sendDatagram(OTHER_PEER_ID, 0);
        assertEquals(established(OTHER_PEER_ID), nextRawFrame());
        assertNoRawFrameFor(NO_FRAME_MILLIS, "no LaunchStatus before the host's link is up");

        sendDatagram(PEER_ID, 0);

        // Linked to both the others of the 3 expected: Ready. The mock reports it after the link
        // report; FA's Lua does not fix the order.
        assertEquals(established(PEER_ID), nextRawFrame());
        assertEquals(launchStatus("Ready"), nextRawFrame());
        // FA's lobby only lets its host send PlayerOption (SendPlayerOptionToServer).
        assertNoFrame("a joiner reports no PlayerOption");
    }

    @Test
    void aJoinerLaunchesOnlyOnceItHasReportedReady() throws Exception {
        launchDelay = Duration.ZERO;
        joining();
        assertEquals(launchStatus("Connecting"), nextRawFrame());

        // With no delay, a launch armed on JoinGame would come at once.
        assertNoFrameFor(NO_LAUNCH_MILLIS, "no launch before the host is linked");

        sendDatagram(PEER_ID, 0);

        assertEquals(established(PEER_ID), nextRawFrame());
        assertEquals(launchStatus("Ready"), nextRawFrame());
        // A loaded machine can hold the launch past the next 2 s report, so that report is skipped.
        assertEquals(GpgNetFrame.of("GameState", "Launching"), nextFrame());
    }

    @Test
    void aDepartureIsAnsweredWithDisconnectedPeerOnly() throws Exception {
        MockGameLifecycle lifecycle = hosting();
        announce(peer, PEER_ID);

        depart(PEER_ID);

        // The engine's PeerDisconnected callback, with the id as a string: not the custom lobby's
        // Disconnected, and no ClearSlot after it, even on the host.
        assertEquals(GpgNetFrame.of("DisconnectedPeer", Integer.toString(PEER_ID)), nextFrame());
        assertNoFrame("the matchmaker lobby clears no slot");
        assertEquals(GameState.HOSTING, lifecycle.getState());
        awaitLogged("stopped sending peer traffic to player " + PEER_ID);
    }

    @Test
    void aDepartureOfAnIdTheGameNeverHadIsAnsweredWithNothing() throws Exception {
        MockGameLifecycle lifecycle = hosting();

        depart(UNKNOWN_PEER_ID);
        // Logged inside the departure action, before anything it would send.
        awaitLogged("Peer (ID: " + UNKNOWN_PEER_ID + ") disconnected but never joined this game");

        assertNoFrame("the engine reports only a peer it had");
        assertEquals(GameState.HOSTING, lifecycle.getState());
    }

    @Test
    void eachPeersFirstDatagramIsReportedOnceAsEstablishedPeer() throws Exception {
        hosting();
        announce(peer, PEER_ID);
        announce(otherPeer, OTHER_PEER_ID);

        sendDatagram(PEER_ID, 0);
        sendDatagram(PEER_ID, 1);
        sendDatagram(OTHER_PEER_ID, 0);

        // One receive thread, one scheduler thread and one state machine, each in order, so a
        // second report for PEER_ID would arrive before OTHER_PEER_ID's. The id is a string chunk.
        // Each report is followed by the host's set for the players seated by then.
        assertEquals(established(PEER_ID), nextFrame());
        drainFrames(2 * MATCHMAKER_PLAYER_OPTION_FRAMES);
        assertEquals(established(OTHER_PEER_ID), nextFrame());
        drainFrames(3 * MATCHMAKER_PLAYER_OPTION_FRAMES);
        assertNoFrame("each peer is reported once");
    }

    @Test
    void aJoinerReportsItsHostOnceTheHostsTrafficArrives() throws Exception {
        joining();

        sendDatagram(PEER_ID, 0);

        assertEquals(established(PEER_ID), nextFrame());
        // FA's lobby only lets its host send PlayerOption (SendPlayerOptionToServer).
        assertNoFrame("a joiner seats nobody and reports no PlayerOption");
    }

    @Test
    void aSenderTheGameNeverHadIsNotReported() throws Exception {
        hosting();
        announce(peer, PEER_ID);

        sendDatagram(UNKNOWN_PEER_ID, 0);
        sendDatagram(PEER_ID, 0);

        // In order as above, so a report for the stranger would arrive first.
        assertEquals(established(PEER_ID), nextFrame());
        drainFrames(2 * MATCHMAKER_PLAYER_OPTION_FRAMES);
        assertNoFrame("only a peer the game has is reported, and seated");
    }

    @Test
    void aLinkFirstHeardAfterLaunchIsNotReported() throws Exception {
        MockGameLifecycle lifecycle = hosting();
        announce(peer, PEER_ID);
        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Only the host is seated, so the set before launch names it alone, as army 1.
        assertPlayerOptions(OWN_ID, OWN_SEAT, 1);
        assertEquals(GpgNetFrame.of("GameState", "Launching"), nextFrame());

        sendDatagram(PEER_ID, 0);
        // Logged by LIVE's own edge for the event, so the event was handled, not dropped.
        awaitLogged("first traffic from peer " + PEER_ID + " arrived after launch");

        assertNoFrame("FA's lobby is gone once the match launches");
    }

    @Test
    void theReceiveThreadNeverWaitsOnTheStateMachine() throws Exception {
        MockGameLifecycle lifecycle = hosting();
        announce(peer, PEER_ID);

        // Held as the shutdown sequence holds it while it waits for the receive thread to stop. A
        // report posted from that thread would block it on the first datagram, and the second
        // would never be read. Waiting for the first line before sending the second keeps the two
        // in order.
        synchronized (lifecycle.stateMachine()) {
            sendDatagram(PEER_ID, 0);
            awaitLogged("first datagram from sender " + PEER_ID + " ");
            sendDatagram(OTHER_PEER_ID, 0);
            awaitLogged("first datagram from sender " + OTHER_PEER_ID + " ");
        }

        assertEquals(established(PEER_ID), nextFrame());
    }

    @Test
    void aCustomLobbyReportsNoLink() throws Exception {
        hosting(CUSTOM_INIT_MODE);
        announce(peer, PEER_ID);
        // The custom lobby's Disconnected, then the peer's PlayerOptions.
        drainFrames(1 + CUSTOM_PLAYER_OPTION_FRAMES);

        sendDatagram(PEER_ID, 0);
        awaitLogged("first datagram from sender " + PEER_ID + " ");

        assertNoRawFrameFor(NO_FRAME_MILLIS, "FA's custom lobby sends no EstablishedPeer");
    }

    /** A matchmaker lifecycle in LOBBY, with the connection open and the lobby socket bound. */
    private MockGameLifecycle inLobby() throws Exception {
        return inLobby(MATCHMAKER_INIT_MODE);
    }

    /**
     * A lifecycle in LOBBY, with the connection open and the lobby socket bound.
     *
     * @param initMode the init mode {@code CreateLobby} names
     */
    private MockGameLifecycle inLobby(final int initMode) throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        config, new GpgNetConnection(gpgnet.port()), launchDelay, null);
        lifecycles.add(lifecycle);
        lifecycle.start();

        gpgnet.start();
        gpgnet.awaitClient();
        // GameState Idle, after the lifecycle's fixed wait before its first send.
        nextFrame();
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "CreateLobby", List.of(initMode, config.lobbyPort(), "Rhiza", OWN_ID, 1)));
        // GameState Lobby.
        nextFrame();
        lifecycle.stateReached(GameState.LOBBY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return lifecycle;
    }

    /** A matchmaker host, which answers {@code HostGame} with no frame. */
    private MockGameLifecycle hosting() throws Exception {
        return hosting(MATCHMAKER_INIT_MODE);
    }

    /**
     * A host, with a custom host's own PlayerOption frames already read.
     *
     * @param initMode the init mode {@code CreateLobby} names
     */
    private MockGameLifecycle hosting(final int initMode) throws Exception {
        MockGameLifecycle lifecycle = inLobby(initMode);
        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        if (initMode == CUSTOM_INIT_MODE) {
            drainFrames(CUSTOM_PLAYER_OPTION_FRAMES);
        }
        lifecycle.stateReached(GameState.HOSTING).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return lifecycle;
    }

    /** A matchmaker joiner whose host is {@link #PEER_ID}, at the relay {@link #peer}. */
    private MockGameLifecycle joining() throws Exception {
        MockGameLifecycle lifecycle = inLobby();
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "JoinGame", List.of(relayAddress(peer), "Smith" + PEER_ID, PEER_ID)));
        // Committed after joinGame has registered the host.
        lifecycle.stateReached(GameState.JOINING).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return lifecycle;
    }

    /**
     * The adapter announcing a peer whose relay is {@code relay}, returning once the game holds the
     * peer. That is the line the {@code ConnectToPeer} action logs: {@code receiveEvent} is
     * synchronized, so a link report for the peer queues behind the rest of that action, which
     * records it. Without the wait a datagram sent next could be reported first, find no such peer,
     * and never be reported again.
     */
    private void announce(final DatagramSocket relay, final int playerId)
            throws IOException, InterruptedException {
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "ConnectToPeer",
                        List.of(relayAddress(relay), "Smith" + playerId, playerId)));
        awaitLogged("New peer (Smith" + playerId + ", ID: " + playerId + ")");
    }

    /** The adapter forwarding faf-server's notice that {@code playerId} left. */
    private void depart(final int playerId) throws IOException {
        gpgnet.sendFrame(new GpgNetFrame("DisconnectFromPeer", List.of(playerId)));
    }

    /**
     * A datagram as {@code senderId}'s game sends it, carrying that game's seat, arriving at this
     * game's lobby port as the adapter would relay it. Sent from {@link #peer}: the receiver goes
     * by the payload's sender.
     */
    private void sendDatagram(final int senderId, final long sequence) throws IOException {
        byte[] payload =
                new GameDatagram(senderId, sequence, System.currentTimeMillis(), seatOf(senderId))
                        .toBytes();
        peer.send(
                new DatagramPacket(
                        payload,
                        payload.length,
                        InetAddress.getLoopbackAddress(),
                        config.lobbyPort()));
    }

    /** The seat {@code playerId}'s datagrams carry. */
    private static Seat seatOf(final int playerId) {
        return switch (playerId) {
            case PEER_ID -> PEER_SEAT;
            case OTHER_PEER_ID -> OTHER_SEAT;
            default -> Seat.DEFAULT;
        };
    }

    /**
     * This test's config, with this game's seat, the given game options and 2 players expected.
     *
     * @param gameOptions the game options a host would send
     */
    private static MockGameConfig newConfig(final Map<String, String> gameOptions)
            throws IOException {
        return newConfig(gameOptions, 2);
    }

    /**
     * This test's config, with this game's seat.
     *
     * @param gameOptions the game options a host would send
     * @param expectedPlayers how many players the match has
     */
    private static MockGameConfig newConfig(
            final Map<String, String> gameOptions, final int expectedPlayers) throws IOException {
        return new MockGameConfig(
                50000,
                TestPorts.freeUdpPort(),
                OWN_ID,
                "Rhiza",
                9001,
                gameOptions,
                0,
                -1,
                0,
                -1,
                OWN_SEAT,
                expectedPlayers);
    }

    /** The frame FA's matchmaker lobby reports a link with: the id as a string chunk. */
    private static GpgNetFrame established(final int playerId) {
        return GpgNetFrame.of("EstablishedPeer", Integer.toString(playerId));
    }

    /** The frame FA's matchmaker lobby reports {@code status} with. */
    private static GpgNetFrame launchStatus(final String status) {
        return GpgNetFrame.of("LaunchStatus", status);
    }

    /** Reads FA's matchmaker set for one seated player: Team, Army, StartSpot, Faction. */
    private void assertPlayerOptions(final int playerId, final Seat seat, final int army)
            throws InterruptedException {
        assertEquals(GpgNetFrame.of("PlayerOption", playerId, "Team", seat.team()), nextFrame());
        assertEquals(GpgNetFrame.of("PlayerOption", playerId, "Army", army), nextFrame());
        assertEquals(
                GpgNetFrame.of("PlayerOption", playerId, "StartSpot", seat.startSpot()),
                nextFrame());
        assertEquals(
                GpgNetFrame.of("PlayerOption", playerId, "Faction", seat.faction()), nextFrame());
    }

    /** A relay socket's address in the {@code host:port} form the adapter names it. */
    private static String relayAddress(final DatagramSocket relay) {
        return "127.0.0.1:" + relay.getLocalPort();
    }

    /**
     * The next frame the game emitted that is not a {@code LaunchStatus}, failing after {@link
     * #TIMEOUT_SECONDS}.
     */
    private GpgNetFrame nextFrame() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            GpgNetFrame frame =
                    gpgnet.pollReceived(
                            Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!frame.command().equals("LaunchStatus")) {
                return frame;
            }
        }
    }

    /** The next frame the game emitted, {@code LaunchStatus} included. */
    private GpgNetFrame nextRawFrame() throws InterruptedException {
        return gpgnet.pollReceived(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * The status of the next frame the game emitted, which must be a {@code LaunchStatus}, within
     * {@link #STATUS_TIMEOUT_SECONDS}.
     */
    private String nextStatus() throws InterruptedException {
        GpgNetFrame frame = gpgnet.pollReceived(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("LaunchStatus", frame.command(), "the next frame: " + frame);
        return frame.stringArg(0);
    }

    /**
     * Consumes {@code count} frames that are not a {@code LaunchStatus}, failing if one is late.
     */
    private void drainFrames(final int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            nextFrame();
        }
    }

    /** Asserts the game emits nothing but {@code LaunchStatus} within {@link #NO_FRAME_MILLIS}. */
    private void assertNoFrame(final String why) throws InterruptedException {
        assertNoFrameFor(NO_FRAME_MILLIS, why);
    }

    /** Asserts the game emits nothing but {@code LaunchStatus} within {@code millis}. */
    private void assertNoFrameFor(final long millis, final String why) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            GpgNetFrame frame = pollOrNull(left);
            if (frame != null && !frame.command().equals("LaunchStatus")) {
                fail(why + ": " + frame);
            }
        }
    }

    /**
     * Asserts the game emits nothing at all, {@code LaunchStatus} included, within {@code millis}.
     */
    private void assertNoRawFrameFor(final long millis, final String why) {
        assertThrows(
                AssertionError.class,
                () -> gpgnet.pollReceived(millis, TimeUnit.MILLISECONDS),
                why);
    }

    /** The next frame within {@code nanos}, or {@code null} if none comes. */
    private GpgNetFrame pollOrNull(final long nanos) throws InterruptedException {
        try {
            return gpgnet.pollReceived(nanos, TimeUnit.NANOSECONDS);
        } catch (AssertionError none) {
            return null;
        }
    }

    /** Bounded wait for a line that starts with {@code prefix}, which another thread logs. */
    private void awaitLogged(final String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (captured.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().startsWith(prefix))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("no line starting '" + prefix + "' within " + TIMEOUT_SECONDS + " s: " + messages());
    }

    /** Every captured message, quoted into a failed assertion. */
    private List<String> messages() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
