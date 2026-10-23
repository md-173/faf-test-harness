package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * The peer frames of FA's matchmaker lobby (WBS-3.2.4.1-fix, #506). FA loads {@code autolobby.lua}
 * rather than the custom {@code lobby.lua} when {@code CreateLobby}'s init mode is 1, which the
 * adapter sends for a matchmaker game, and the two answer peer frames differently. The custom
 * lobby's are pinned by {@link LifecyclePeerConnectTest} and {@link LifecyclePeerDisconnectTest}.
 *
 * <p>As there, frames arrive over a real socket from {@link ScriptedGpgNetServer} and the game's
 * answers are read back from it, so the dispatcher registration is under test too.
 */
final class LifecycleMatchmakerLobbyTest {

    /** {@code CreateLobby}'s init mode for a matchmaker game: the adapter's {@code AUTO}. */
    private static final int MATCHMAKER_INIT_MODE = 1;

    /** Budget for a frame, a log line or a state the game is expected to reach. */
    private static final long TIMEOUT_SECONDS = 5;

    /**
     * How long a frame that must not come is waited for. The game sends its answers inside one
     * transition action, so anything it was going to send has arrived well within this.
     */
    private static final long NO_FRAME_MILLIS = 300;

    /** PlayerOption frames the game emits per player it configures as host: Army through Color. */
    private static final int PLAYER_OPTION_FRAMES = 5;

    /** This game's player id. As host it is army 1, so its first peer gets army 2. */
    private static final int OWN_ID = 1;

    /** The first peer a host is told about, and the host a joiner joins. */
    private static final int PEER_ID = 2;

    /** A second peer. */
    private static final int OTHER_PEER_ID = 3;

    private MockGameConfig config;

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
        config =
                new MockGameConfig(
                        50000,
                        TestPorts.freeUdpPort(),
                        OWN_ID,
                        "Rhiza",
                        9001,
                        Map.of(),
                        0,
                        -1,
                        0,
                        -1);
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
    void aHostAnswersConnectToPeerWithThatPeersPlayerOptionsOnly() throws Exception {
        MockGameLifecycle lifecycle = hosting();

        announce(peer, PEER_ID);

        // Not the custom lobby's Disconnected first: the matchmaker lobby's ConnectToPeer only
        // connects (autolobby.lua).
        assertEquals(GpgNetFrame.of("PlayerOption", PEER_ID, "Army", 2), nextFrame());
        drainFrames(PLAYER_OPTION_FRAMES - 1);
        assertEquals(GameState.HOSTING, lifecycle.getState());
    }

    @Test
    void aJoinerAnswersJoinGameAndConnectToPeerWithNothing() throws Exception {
        MockGameLifecycle lifecycle = joining();

        announce(otherPeer, OTHER_PEER_ID);
        // Logged inside the ConnectToPeer action, before anything it would send.
        awaitLogged("New peer (Smith" + OTHER_PEER_ID + ", ID: " + OTHER_PEER_ID + ")");

        assertNoFrame("the matchmaker lobby answers neither JoinGame nor ConnectToPeer");
        assertEquals(GameState.JOINING, lifecycle.getState());
    }

    /** A matchmaker lifecycle in LOBBY, with the connection open and the lobby socket bound. */
    private MockGameLifecycle inLobby() throws Exception {
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(config, new GpgNetConnection(gpgnet.port()), null, null);
        lifecycles.add(lifecycle);
        lifecycle.start();

        gpgnet.start();
        gpgnet.awaitClient();
        // GameState Idle, after the lifecycle's fixed wait before its first send.
        nextFrame();
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "CreateLobby",
                        List.of(MATCHMAKER_INIT_MODE, config.lobbyPort(), "Rhiza", OWN_ID, 1)));
        // GameState Lobby.
        nextFrame();
        lifecycle.stateReached(GameState.LOBBY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return lifecycle;
    }

    /** A matchmaker host, its own PlayerOption frames already read. */
    private MockGameLifecycle hosting() throws Exception {
        MockGameLifecycle lifecycle = inLobby();
        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scmp_007")));
        drainFrames(PLAYER_OPTION_FRAMES);
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

    /** The adapter announcing a peer whose relay is {@code relay}. */
    private void announce(final DatagramSocket relay, final int playerId) throws IOException {
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "ConnectToPeer",
                        List.of(relayAddress(relay), "Smith" + playerId, playerId)));
    }

    /** A relay socket's address in the {@code host:port} form the adapter names it. */
    private static String relayAddress(final DatagramSocket relay) {
        return "127.0.0.1:" + relay.getLocalPort();
    }

    /** The next frame the game emitted, failing after {@link #TIMEOUT_SECONDS}. */
    private GpgNetFrame nextFrame() throws InterruptedException {
        return gpgnet.pollReceived(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Consumes {@code count} frames the game emitted, failing on one that does not come. */
    private void drainFrames(final int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            nextFrame();
        }
    }

    /** Asserts the game emits nothing more within {@link #NO_FRAME_MILLIS}. */
    private void assertNoFrame(final String why) {
        assertThrows(
                AssertionError.class,
                () -> gpgnet.pollReceived(NO_FRAME_MILLIS, TimeUnit.MILLISECONDS),
                why);
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
