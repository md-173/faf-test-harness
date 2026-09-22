package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * The host and a joiner in one session report the same {@code GameResult} frames, naming exactly
 * the armies the host assigned (WBS-3.2.4.3-fix, #384).
 *
 * <p><b>Why identical is the contract.</b> FA sends the whole result table from every client: its
 * sim declares a result for every army and {@code UserSync.lua} forwards each one. faf-server keeps
 * every player's report per army and resolves each army across them ({@code GameResultReports}),
 * unanimously when they agree. So every game reports every army, with the same outcome for it. The
 * army ids come from the host alone, because faf-server accepts {@code PlayerOption} only from the
 * host and drops a result for any army the host never assigned. {@code MockGameLifecycle.gameEnds}
 * records the rest of the reasoning.
 *
 * <p><b>Why a joiner agrees without knowing its own army.</b> It never receives a {@code
 * PlayerOption}. It reports armies {@code 1..N}, with an outcome that depends on the army number
 * alone, and that matches the host only while two separate rules stay in step: the arrival order
 * assignment in {@code sendPlayerOptions} and the range and team rule in {@code gameEnds}. Nothing
 * else notices if they drift, so the expected frames are built from the host's own {@code
 * PlayerOption} frames, the numbering faf-server records, rather than restated here.
 *
 * <p><b>Why two games rather than one per player.</b> The property is that a host and a joiner
 * holding the same number of peers send the same frames, and a third or fourth joiner ends up
 * holding as many peers as the first, which is all {@code gameEnds} reads. The player count still
 * varies, because it moves the range and, at three players, leaves the teams uneven. Each game is
 * told about the others the way faf-server does it ({@code connect_to_host} and {@code
 * connect_to_peer} in {@code gameconnection.py}): a newcomer gets {@code JoinGame} for the host and
 * {@code ConnectToPeer} for every other player already present, and each of those gets {@code
 * ConnectToPeer} for the newcomer. The joiner here arrives second, so every later peer reaches it
 * the second way.
 *
 * <p><b>What proves a peer was counted.</b> A joiner answers {@code ConnectToPeer} with nothing on
 * the wire, and {@code launchMatch()} runs on this thread without queueing behind frames still
 * unread on the socket, so a frame sent just before it can land in LIVE and be dropped, leaving
 * that game an army short. The barrier is {@code GameTrafficSession}'s "sending peer traffic to
 * player" line, which the lifecycle reaches only after {@code peers.add}. Its earlier "New peer"
 * line would not do, since it is logged before the add. Each game has its own stub relay socket, so
 * the address in that line says which game logged it.
 *
 * <p>Pinning the host's frames value by value is {@code LifecycleSetupTest}'s job. This class
 * asserts only what the two games' results have to say about each other.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class PeerResultAgreementTest {

    /**
     * Player ids in arrival order: the host, the joiner under test, then two players who exist only
     * as peers. Deliberately unlike army numbers, so a game that reported player ids as armies
     * would fail rather than pass by coincidence.
     */
    private static final int[] PLAYER_IDS = {101, 202, 303, 404};

    /** Budget for a frame or a log line that should arrive almost immediately. */
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(5);

    /** Budget for a state the FSM should reach almost immediately. */
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(5);

    /** Poll slice for the registration barrier. */
    private static final Duration POLL_SLICE = Duration.ofMillis(10);

    /** The hosting game. */
    private Game host;

    /** The joining game, second to arrive. */
    private Game joiner;

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /** Captures both games' log records, for the registration barrier; they share one logger. */
    private ListAppender<ILoggingEvent> captured;

    /** One mock game: its scripted adapter, the stub relay its peers live at, and its lifecycle. */
    private static final class Game {

        /** Stands in for this game's ICE adapter. */
        private final ScriptedGpgNetServer gpgnet;

        /**
         * The address every peer of this game is announced at. Never read: it exists so the peer
         * traffic has a real destination and so the registration line names this game.
         */
        private final DatagramSocket relay;

        /** This game's config; no auto-launch, and no injected crash, which would halt the JVM. */
        private final MockGameConfig config;

        /** The game under test, driven by hand: no launch delay and no match duration. */
        private final MockGameLifecycle lifecycle;

        private Game(final int playerId, final int lobbyPort) throws IOException {
            this.gpgnet = new ScriptedGpgNetServer();
            this.relay = new DatagramSocket(0);
            this.config =
                    new MockGameConfig(
                            50000,
                            lobbyPort,
                            playerId,
                            loginOf(playerId),
                            9001,
                            Map.of(),
                            -1,
                            -1,
                            0,
                            -1);
            this.lifecycle =
                    new MockGameLifecycle(config, new GpgNetConnection(gpgnet.port()), null, null);
        }

        /** Where this game's peers live, in the {@code host:port} form the adapter supplies. */
        private String relayAddress() {
            return "127.0.0.1:" + relay.getLocalPort();
        }

        /** Lifecycle first: stopping the server first would end the game on its reader thread. */
        private void close() {
            lifecycle.shutdown().run();
            relay.close();
            gpgnet.stop();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        // Two reader threads, their traffic threads and this one all touch the list.
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);

        // Both lobby ports stay reserved until both games exist, and are released for their
        // lifecycles to bind on CreateLobby. Taken one at a time, the joiner's relay or its lobby
        // port could land on the port just released for the host's, and a bind would then fail.
        try (DatagramSocket hostLobby = new DatagramSocket(0);
                DatagramSocket joinerLobby = new DatagramSocket(0)) {
            host = new Game(PLAYER_IDS[0], hostLobby.getLocalPort());
            joiner = new Game(PLAYER_IDS[1], joinerLobby.getLocalPort());
        }
    }

    @AfterEach
    void tearDown() {
        if (joiner != null) {
            joiner.close();
        }
        if (host != null) {
            host.close();
        }
        captured.stop();
        root.detachAppender(captured);
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {2, 3, 4})
    void bothGamesReportTheArmiesTheHostAssigned(final int players) throws Exception {
        // Both lifecycles start before either is driven, so their 500 ms waits before GameState
        // Idle overlap rather than add up. Servers first, as in LifecycleSetupTest's setup.
        host.gpgnet.start();
        joiner.gpgnet.start();
        host.lifecycle.start();
        joiner.lifecycle.start();
        reachLobby(host);
        reachLobby(joiner);

        host.gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        awaitState(host, GameState.HOSTING);
        // JOINING is committed after joinGame has counted the host, so reaching it is the barrier.
        joiner.gpgnet.sendFrame(
                new GpgNetFrame(
                        "JoinGame",
                        List.of(joiner.relayAddress(), loginOf(PLAYER_IDS[0]), PLAYER_IDS[0])));
        awaitState(joiner, GameState.JOINING);

        // Every later arrival is announced to the host, and to each joiner already present, which
        // the joiner under test is from the third player on.
        for (int i = 1; i < players; i++) {
            announce(host, PLAYER_IDS[i]);
        }
        for (int i = 2; i < players; i++) {
            announce(joiner, PLAYER_IDS[i]);
        }

        List<GpgNetFrame> hostFrames = playOut(host);
        List<GpgNetFrame> joinerFrames = playOut(joiner);

        // Compared as ordered lists because gameEnds reports in army order on every run, so equal
        // lists are the plainest statement of agreement. faf-server itself does not care about
        // order: it groups the reports by army.
        List<GpgNetFrame> expected = resultsForHostAssignment(hostFrames, players);
        assertEquals(
                expected,
                results(hostFrames),
                "the host should report every army it assigned, each with its team's result");
        assertEquals(
                expected,
                results(joinerFrames),
                "the joiner should report exactly what the host does: faf-server resolves each"
                        + " army by vote across reporters, and army ids are the host's");
    }

    /** Drives one game to LOBBY, where it binds the lobby socket its peer traffic leaves from. */
    private void reachLobby(final Game game) throws Exception {
        game.gpgnet.awaitClient();
        awaitState(game, GameState.IDLE);
        game.gpgnet.sendFrame(
                new GpgNetFrame(
                        "CreateLobby",
                        List.of(
                                0,
                                game.config.lobbyPort(),
                                game.config.playerLogin(),
                                game.config.playerId(),
                                1)));
        awaitState(game, GameState.LOBBY);
    }

    /**
     * Tells a game about a peer with {@code ConnectToPeer}, then waits until it counts that peer.
     */
    private void announce(final Game game, final int peerId) throws Exception {
        game.gpgnet.sendFrame(
                new GpgNetFrame(
                        "ConnectToPeer", List.of(game.relayAddress(), loginOf(peerId), peerId)));
        awaitRegistered(game, peerId);
    }

    /**
     * Waits for the line the traffic session logs on registering a peer, which the lifecycle
     * reaches only after adding that peer to the list {@code gameEnds} sizes its results from.
     */
    private void awaitRegistered(final Game game, final int peerId) throws InterruptedException {
        String line = "sending peer traffic to player " + peerId + " at " + game.relayAddress();
        long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
        do {
            for (ILoggingEvent event : captured.list) {
                if (line.equals(event.getFormattedMessage())) {
                    return;
                }
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);

        fail(
                "player "
                        + game.config.playerId()
                        + " never counted peer "
                        + peerId
                        + ": no '"
                        + line
                        + "' within "
                        + FRAME_TIMEOUT
                        + ". A 'failed to bind' line instead means another process took the lobby"
                        + " port after setUp released it. No line at all, outside Gradle, can mean"
                        + " LOG_LEVEL is above INFO, which mock-game's build pins for its tests.");
    }

    /** Launches and ends the game's match, returning every frame the game sent, in order. */
    private List<GpgNetFrame> playOut(final Game game) throws Exception {
        game.lifecycle.launchMatch();
        awaitState(game, GameState.LIVE);
        game.lifecycle.endMatch();
        awaitState(game, GameState.ENDED);

        // ENDED is committed only after gameEnds has written every closing frame, so all of them
        // are on the wire by now, and GameState Ended is the last.
        GpgNetFrame last = GpgNetFrame.of("GameState", "Ended");
        List<GpgNetFrame> sent = new ArrayList<>();
        GpgNetFrame frame;
        do {
            frame = game.gpgnet.pollReceived(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            sent.add(frame);
        } while (!frame.equals(last));
        return sent;
    }

    /** Waits for a state, failing with its budget rather than hanging. */
    private static void awaitState(final Game game, final GameState state) throws Exception {
        game.lifecycle.stateReached(state).get(STATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The {@code GameResult} frames every game in the session must send, derived from what the host
     * told the server: one per army it assigned, in army order, reporting victory for the armies on
     * army 1's team and defeat for the rest. That last rule is the mock's fixed result, stated in
     * {@code gameEnds}; the armies and their teams come from the host's frames.
     */
    private static List<GpgNetFrame> resultsForHostAssignment(
            final List<GpgNetFrame> hostFrames, final int players) {
        Map<Integer, Integer> armyByPlayer = playerOption(hostFrames, "Army");
        Map<Integer, Integer> teamByPlayer = playerOption(hostFrames, "Team");
        assertEquals(players, armyByPlayer.size(), "the host should assign every player an army");

        Map<Integer, Integer> teamByArmy = new TreeMap<>();
        armyByPlayer.forEach((player, army) -> teamByArmy.put(army, teamByPlayer.get(player)));
        Integer winningTeam = teamByArmy.get(1);
        assertNotNull(
                winningTeam, "the host should give army 1 a team, the winning one: " + teamByArmy);

        List<GpgNetFrame> expected = new ArrayList<>();
        teamByArmy.forEach(
                (army, team) ->
                        expected.add(
                                winningTeam.equals(team)
                                        ? GpgNetFrame.of("GameResult", army, "victory 10")
                                        : GpgNetFrame.of("GameResult", army, "defeat -10")));
        return expected;
    }

    /** The value the host set for one {@code PlayerOption} key, by player id. */
    private static Map<Integer, Integer> playerOption(
            final List<GpgNetFrame> frames, final String key) {
        Map<Integer, Integer> byPlayer = new HashMap<>();
        for (GpgNetFrame frame : frames) {
            if (frame.command().equals("PlayerOption") && frame.stringArg(1).equals(key)) {
                byPlayer.put(frame.intArg(0), frame.intArg(2));
            }
        }
        return byPlayer;
    }

    /** The {@code GameResult} frames among those a game sent, in the order it sent them. */
    private static List<GpgNetFrame> results(final List<GpgNetFrame> frames) {
        return frames.stream().filter(frame -> frame.command().equals("GameResult")).toList();
    }

    /** A login for a player id, since the frames carry both. */
    private static String loginOf(final int playerId) {
        return "Player" + playerId;
    }
}
