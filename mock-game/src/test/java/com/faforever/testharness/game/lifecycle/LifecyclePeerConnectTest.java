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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

public final class LifecyclePeerConnectTest {

    /** This test's config, with a lobby port free at setup time (WBS-4.3.2 binds it for real). */
    private MockGameConfig config;

    private ScriptedGpgNetServer gpgnet;

    /**
     * Stands in for the peer's relay socket inside the ICE adapter. A real bound socket rather than
     * a made-up address: since WBS-4.3.2 the game starts sending to whatever a peer frame names,
     * and an unroutable destination would log a send failure on every round.
     */
    private DatagramSocket peer;

    /** A second stub relay socket, for the test that announces two peers. */
    private DatagramSocket secondPeer;

    /** Every lifecycle a test built, torn down after it so no lobby socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        config = new MockGameConfig(50000, TestPorts.freeUdpPort(), 1, "Rhiza", 9001, Map.of(), 0);
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);
        secondPeer = new DatagramSocket(0);
    }

    @AfterEach
    void teardown() {
        lifecycles.forEach(lifecycle -> lifecycle.shutdown().run());
        lifecycles.clear();
        peer.close();
        secondPeer.close();
        gpgnet.stop();
    }

    /** Builds a lifecycle on this test's config and records it for teardown. */
    private MockGameLifecycle lifecycleOn(final GpgNetConnection connection) {
        MockGameLifecycle created = new MockGameLifecycle(config, connection, null, null);
        lifecycles.add(created);
        return created;
    }

    /** The stub peer's address in the {@code host:port} form the adapter supplies. */
    private String peerAddress() {
        return "127.0.0.1:" + peer.getLocalPort();
    }

    /** The second stub peer's address, same form. */
    private String secondPeerAddress() {
        return "127.0.0.1:" + secondPeer.getLocalPort();
    }

    // Tests ConnectToPeer messages are received correctly from the host side.
    @Test
    void connectToPeer() throws Exception {
        // No delay and match duration so that those don't interfere.
        MockGameLifecycle lifecycle = lifecycleOn(new GpgNetConnection(gpgnet.port()));

        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        assertMessage("PlayerOption", config.playerId(), "Army", 1);
        assertMessage("PlayerOption", config.playerId(), "Team", 1);
        assertMessage("PlayerOption", config.playerId(), "StartSpot", 1);
        assertMessage("PlayerOption", config.playerId(), "Faction", 1);
        assertMessage("PlayerOption", config.playerId(), "Color", 1);
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", 2)));
        assertMessage("PlayerOption", 2, "Army", 2);
        assertMessage("PlayerOption", 2, "Team", 2);
        assertMessage("PlayerOption", 2, "StartSpot", 2);
        assertMessage("PlayerOption", 2, "Faction", 2);
        assertMessage("PlayerOption", 2, "Color", 2);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    // Tests ConnectToPeer messages are received correctly from the joiner side. Currently, these
    // produce no actual
    // side-effect, so we must capture the log.
    @Test
    void joinerConnectToPeer() throws Exception {
        // No delay and match duration so that those don't interfere.
        MockGameLifecycle lifecycle = lifecycleOn(new GpgNetConnection(gpgnet.port()));
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        // The receiver and ticker threads log while this is attached to the root logger, so the
        // default ArrayList would be read and written concurrently.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();

        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of(peerAddress(), "Smith", 2)));
        lifecycle.stateReached(GameState.JOINING).get(1, TimeUnit.SECONDS);

        // Start sending logs to list.
        root.addAppender(appender);

        gpgnet.sendFrame(
                new GpgNetFrame("ConnectToPeer", List.of(secondPeerAddress(), "ProGamer", 3)));

        // We should not receive any PlayerOption messages here (ScriptedGpgNetServer throws an
        // AssertionError when it times out).
        assertThrows(AssertionError.class, () -> gpgnet.pollReceived(1, TimeUnit.SECONDS));

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);

        // Stop sending logs to list.
        appender.stop();
        root.detachAppender(appender);

        Predicate<ILoggingEvent> pred =
                e ->
                        e.getMessage().contains("New peer")
                                && e.getArgumentArray()[2].equals(secondPeerAddress());
        assertTrue(appender.list.stream().anyMatch(pred));
    }

    private void assertMessage(String expectedCommand, Object... expectedArgs) {
        GpgNetFrame received = null;
        try {
            received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Did not receive frame", e);
        }
        assertEquals(expectedCommand, received.command());
        assertEquals(
                expectedArgs.length,
                received.args().size(),
                "Received argument count doesn't match expected");
        for (int i = 0; i < expectedArgs.length; i++) {
            assertEquals(expectedArgs[i], received.args().get(i));
        }
    }
}
