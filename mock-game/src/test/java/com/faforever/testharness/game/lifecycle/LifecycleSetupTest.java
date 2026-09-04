package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.faforever.testharness.game.TestPorts;
import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class LifecycleSetupTest {

    /** This test's config, with a lobby port free at setup time (WBS-4.3.2 binds it for real). */
    private MockGameConfig config;

    private ScriptedGpgNetServer gpgnet;
    private MockGameLifecycle lifecycle;

    /**
     * Stands in for a peer's relay socket inside the ICE adapter, so the traffic a peer frame
     * starts has a real destination rather than an address nothing is listening on.
     */
    private DatagramSocket peer;

    /** Every lifecycle a test built, torn down after it so no lobby socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        config = new MockGameConfig(50000, TestPorts.freeUdpPort(), 1, "Rhiza", 9001, Map.of(), 0);
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);
        lifecycle =
                lifecycleOn(
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
    }

    @AfterEach
    void teardown() {
        lifecycles.forEach(created -> created.shutdown().run());
        lifecycles.clear();
        peer.close();
        gpgnet.stop();
    }

    /** Builds a lifecycle on this test's config and records it for teardown. */
    private MockGameLifecycle lifecycleOn(
            final GpgNetConnection connection,
            final Duration launchDelay,
            final Duration matchDuration) {
        MockGameLifecycle created =
                new MockGameLifecycle(config, connection, launchDelay, matchDuration);
        lifecycles.add(created);
        return created;
    }

    /** The stub peer's address in the {@code host:port} form the adapter supplies. */
    private String peerAddress() {
        return "127.0.0.1:" + peer.getLocalPort();
    }

    @Test
    // The FSM's starting state, asserted against a port with no listener. The shared fixture binds
    // its ServerSocket in its constructor, and a bound socket completes TCP handshakes out of the
    // listen backlog whether or not start() has been called — so a lifecycle pointed at it can
    // connect and leave INITIALIZING before the assertion runs, which is what made this a CI flake.
    // With nothing listening, the bounded connect retry holds INITIALIZING for its full window.
    void startsInInitializing() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            deadPort = socket.getLocalPort();
        }

        MockGameLifecycle unconnected = lifecycleOn(new GpgNetConnection(deadPort), null, null);

        assertEquals(GameState.INITIALIZING, unconnected.getState());
    }

    @Test
    // Tests initial gpgnet connection causes a GameState("Idle") and following CreateLobby causes a
    // GameState("Lobby"), with similar internal state.
    void gpgnetSetup() throws Exception {
        gpgnet.start();

        gpgnet.awaitClient();
        assertMessage("GameState", "Idle");
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        assertMessage("GameState", "Lobby");
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);
    }

    @Test
    void hostBranch() throws Exception {
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

        lifecycle.launchMatch();
        assertMessage("GameState", "Launching");
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        assertMessage("GameResult", 1, "victory 10");
        assertMessageCommand("JsonStats");
        assertMessage("GameEnded");
        assertMessage("GameState", "Ended");

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    @Test
    void joinBranch() throws Exception {
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

        lifecycle.launchMatch();
        assertMessage("GameState", "Launching");
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        // Two GameResult messages as a joiner always has the host as a peer.
        assertMessage("GameResult", 1, "victory 10");
        assertMessage("GameResult", 2, "defeat -10");
        assertMessageCommand("JsonStats");
        assertMessage("GameEnded");
        assertMessage("GameState", "Ended");

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
    }

    @Test
    void delayedStartAndEnd() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();

        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        // No need to send command, should become LIVE within 1 second (2 for error).
        lifecycle.stateReached(GameState.LIVE).get(2, TimeUnit.SECONDS);

        // No need to send command, should become ENDED within 1 second (2 for error).
        lifecycle.stateReached(GameState.ENDED).get(2, TimeUnit.SECONDS);
    }

    @Test
    void cleanShutdown() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
        assertEquals(MockGameLifecycle.ExitStatus.OK, lifecycle.getExitStatus());
    }

    @Test
    void perArmyGameResult() throws Exception {
        gpgnet.start();
        gpgnet.awaitClient();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        // Drop PlayerOption frames
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("ConnectToPeer", List.of(peerAddress(), "Smith", 2)));
        // Drop PlayerOption frames for new player
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        lifecycle.launchMatch();
        // Drop frame
        gpgnet.pollReceived(1, TimeUnit.SECONDS);
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        lifecycle.endMatch();

        // Two GameResults
        assertMessage("GameResult", 1, "victory 10");
        assertMessage("GameResult", 2, "defeat -10");
        assertMessageCommand("JsonStats");
        assertMessage("GameEnded");
        assertMessage("GameState", "Ended");

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
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

    private void assertMessageCommand(String expectedCommand) {
        GpgNetFrame received = null;
        try {
            received = gpgnet.pollReceived(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Did not receive frame", e);
        }
        assertEquals(expectedCommand, received.command());
    }
}
