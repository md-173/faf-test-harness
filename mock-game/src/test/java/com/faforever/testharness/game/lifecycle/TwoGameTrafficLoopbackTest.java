package com.faforever.testharness.game.lifecycle;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Two mock games exchanging traffic in both directions (WBS-4.3.2), with the ICE adapters taken out
 * of the path: each game is told the other's lobby port as its peer address, which is exactly what
 * an adapter relay would forward to. Everything either game does is real — the GPGNet frames, the
 * socket, the cadence, the receiver, and the log line.
 *
 * <p><b>Why this exists next to the live test.</b> {@code TwoPeerSessionLiveTest} is the real
 * article, but it needs two seeded lobby accounts, the {@code faf-uid} binary and a reachable
 * lobby, so it self-skips on an unequipped machine and never runs in CI. Its verdict rests on a
 * chain — progress line, log capture, regex, thresholds, "still advancing" — that is otherwise
 * unexercised until someone runs it by hand. This test pins that chain with <em>the same pattern
 * and the same thresholds</em>, so a change that would silently stop the live test from ever
 * matching fails here first, in the fast suite.
 *
 * <p>What it deliberately does not cover, because no adapter is involved: ICE establishment, the
 * adapter's pre-connection drop window, and the {@code 'd'} prefix the adapter adds and strips.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class TwoGameTrafficLoopbackTest {

    /** The hosting game's player id, as a lobby would have assigned it. */
    private static final int HOST_ID = 101;

    /** The joining game's player id. */
    private static final int JOINER_ID = 202;

    /**
     * The progress line, copied verbatim from {@code TwoPeerSessionLiveTest} (mock-client). The two
     * copies are deliberate — an independent restatement is what gives this test its value — but
     * they are in different modules and nothing links them, so <b>a change to either the format
     * string in {@code GameTrafficSession} or to one copy of this pattern must be made to both</b>.
     * This copy is the one that fails fast; the live copy may not run for weeks.
     */
    private static final Pattern PROGRESS_LINE =
            Pattern.compile(
                    "player (\\d+) peer traffic from player (\\d+): (\\d+) datagrams, "
                            + "highest sequence (-?\\d+), gaps (\\d+)");

    /** Datagrams a direction must carry, matching the live test's threshold. */
    private static final int MIN_DATAGRAMS = 3;

    /** Progress lines required per direction, matching the live test. */
    private static final int MIN_PROGRESS_SAMPLES = 2;

    /** Budget for both directions to be proven; generous against a 1 s progress interval. */
    private static final Duration TRAFFIC_TIMEOUT = Duration.ofSeconds(30);

    /** Budget for a state the FSM should reach almost immediately. */
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(5);

    /** Poll slice for the bounded wait. */
    private static final Duration POLL_SLICE = Duration.ofMillis(100);

    /** The hosting game. */
    private Game host;

    /** The joining game. */
    private Game joiner;

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /** Captures both games' log records; they share this JVM's root logger. */
    private ListAppender<ILoggingEvent> captured;

    /** One mock game: its scripted adapter, its config, and its lifecycle. */
    private static final class Game {

        private final ScriptedGpgNetServer gpgnet;
        private final MockGameConfig config;
        private final MockGameLifecycle lifecycle;

        private Game(final int playerId, final String login) throws IOException {
            this.gpgnet = new ScriptedGpgNetServer();
            this.config =
                    new MockGameConfig(
                            50000, TestPorts.freeUdpPort(), playerId, login, 9001, Map.of(), 0);
            this.lifecycle =
                    new MockGameLifecycle(config, new GpgNetConnection(gpgnet.port()), null, null);
        }

        /** This game's lobby address, in the {@code host:port} form the adapter supplies. */
        private String lobbyAddress() {
            return "127.0.0.1:" + config.lobbyPort();
        }

        private void close() {
            lifecycle.shutdown().run();
            gpgnet.stop();
        }
    }

    /**
     * One captured progress line, parsed — same shape the live test parses.
     *
     * @param receiverId the game that logged the line
     * @param senderId the peer whose datagrams it counted
     * @param datagrams how many it had attributed to that peer
     * @param highestSequence the highest sequence number seen from that peer
     */
    private record TrafficSample(
            int receiverId, int senderId, long datagrams, long highestSequence) {
        @Override
        public String toString() {
            return "player "
                    + receiverId
                    + " <- player "
                    + senderId
                    + ": "
                    + datagrams
                    + " datagrams, highest sequence "
                    + highestSequence;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        // Two receive threads, two ticker threads and the test thread all touch this.
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);

        host = new Game(HOST_ID, "Alice");
        joiner = new Game(JOINER_ID, "Bob");
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

    @Test
    void bothGamesReportReceivingTheOthersDatagrams() throws Exception {
        // Each game learns the other's lobby port as its peer address. A real session would name
        // the adapter's per-peer relay port instead; the adapter forwards to the far game's lobby
        // port unchanged, so this is the same path with the two relays removed.
        //
        // The host is told about the joiner before the joiner has bound anything, so its first
        // rounds land on a closed port. That is deliberate — it mirrors the adapter dropping
        // everything sent before ICE completes — and it means this exchange starts mid-sequence
        // with a non-zero gap count. Nothing here may assert zero gaps or a starting sequence.
        reachLobby(host);
        host.gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        awaitState(host, GameState.HOSTING);
        host.gpgnet.sendFrame(
                new GpgNetFrame("ConnectToPeer", List.of(joiner.lobbyAddress(), "Bob", JOINER_ID)));

        reachLobby(joiner);
        joiner.gpgnet.sendFrame(
                new GpgNetFrame("JoinGame", List.of(host.lobbyAddress(), "Alice", HOST_ID)));
        awaitState(joiner, GameState.JOINING);

        awaitTwoWayTraffic();
    }

    /** Drives one game to LOBBY, which is where it binds its lobby socket. */
    private void reachLobby(final Game game) throws Exception {
        game.gpgnet.start();
        game.gpgnet.awaitClient();
        game.gpgnet.pollReceived(1, TimeUnit.SECONDS); // GameState Idle
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
        game.gpgnet.pollReceived(1, TimeUnit.SECONDS); // GameState Lobby
    }

    /** Waits for a state, failing with its budget rather than hanging. */
    private void awaitState(final Game game, final GameState state) throws Exception {
        game.lifecycle.stateReached(state).get(STATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The live test's own check, run against real traffic: both directions need {@link
     * #MIN_PROGRESS_SAMPLES} progress lines reaching {@link #MIN_DATAGRAMS} datagrams with the
     * highest sequence advancing between the first and the last.
     */
    private void awaitTwoWayTraffic() throws InterruptedException {
        long deadline = System.nanoTime() + TRAFFIC_TIMEOUT.toNanos();
        do {
            if (proven(HOST_ID, JOINER_ID) && proven(JOINER_ID, HOST_ID)) {
                return;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);

        fail(
                "no two-way traffic within "
                        + TRAFFIC_TIMEOUT
                        + "; host received: "
                        + samples(HOST_ID, JOINER_ID)
                        + "; joiner received: "
                        + samples(JOINER_ID, HOST_ID));
    }

    /** Whether one direction has been proven, on the live test's terms. */
    private boolean proven(final int receiverId, final int senderId) {
        List<TrafficSample> seen = samples(receiverId, senderId);
        if (seen.size() < MIN_PROGRESS_SAMPLES) {
            return false;
        }
        TrafficSample oldest = seen.get(0);
        TrafficSample newest = seen.get(seen.size() - 1);
        return newest.datagrams() >= MIN_DATAGRAMS
                && newest.highestSequence() > oldest.highestSequence();
    }

    /** Every progress line captured so far for one direction, oldest first. */
    private List<TrafficSample> samples(final int receiverId, final int senderId) {
        List<TrafficSample> found = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            Matcher matcher = PROGRESS_LINE.matcher(event.getFormattedMessage());
            if (!matcher.find()) {
                continue;
            }
            TrafficSample sample =
                    new TrafficSample(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Long.parseLong(matcher.group(3)),
                            Long.parseLong(matcher.group(4)));
            if (sample.receiverId() == receiverId && sample.senderId() == senderId) {
                found.add(sample);
            }
        }
        return found;
    }
}
