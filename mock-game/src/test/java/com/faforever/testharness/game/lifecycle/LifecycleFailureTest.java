package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class LifecycleFailureTest {

    /** This test's config, with a lobby port free at setup time (WBS-4.3.2 binds it for real). */
    private MockGameConfig config;

    private ScriptedGpgNetServer gpgnet;

    /** Every lifecycle a test built, torn down after it so no lobby socket outlives the test. */
    private final List<MockGameLifecycle> lifecycles = new ArrayList<>();

    @BeforeEach
    void setupServer() throws IOException {
        config = new MockGameConfig(50000, TestPorts.freeUdpPort(), 1, "Rhiza", 9001, Map.of(), 0);
        gpgnet = new ScriptedGpgNetServer();
        gpgnet.start();
    }

    @AfterEach
    void teardownServer() {
        lifecycles.forEach(lifecycle -> lifecycle.shutdown().run());
        lifecycles.clear();
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

    @Test
    void timeoutWhenServerUnreachable() throws Exception {
        // Stop the server now.
        gpgnet.stop();
        // Set the timeout to 1 second.
        MockGameLifecycle lifecycle =
                new MockGameLifecycle(
                        config,
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycles.add(lifecycle);
        // Wait for 2 seconds, timeout should occur.
        lifecycle.stateReached(GameState.ENDED).get(2, TimeUnit.SECONDS);
        assertEquals(MockGameLifecycle.ExitStatus.SERVER_NOT_CONNECTED, lifecycle.getExitStatus());
    }

    @Test
    void serverDisconnectionOnIdle() throws Exception {
        MockGameLifecycle lifecycle =
                lifecycleOn(
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);
        gpgnet.stop();
        lifecycle.stateReached(GameState.ENDED).get(10, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.SERVER_CONNECTION_LOST, lifecycle.getExitStatus());
    }

    @Test
    void serverDisconnectionOnLive() throws Exception {
        // No delay and match duration so that those don't intefere.
        MockGameLifecycle lifecycle = lifecycleOn(new GpgNetConnection(gpgnet.port()), null, null);
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);
        gpgnet.stop();
        lifecycle.stateReached(GameState.ENDED).get(10, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.SERVER_CONNECTION_LOST, lifecycle.getExitStatus());
    }

    @Test
    void connectionClosedOnLive() throws Exception {
        GpgNetConnection conn = new GpgNetConnection(gpgnet.port());
        // No delay and match duration so that those don't intefere.
        MockGameLifecycle lifecycle = lifecycleOn(conn, null, null);
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);
        conn.close();
        // A local close does not cause the lifecycle to reach ended.
        assertThrows(
                TimeoutException.class,
                () -> lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS));
    }

    /**
     * A send that fails because the socket is gone reports connection loss, not the initial FAILED.
     *
     * <p>Closing locally is what makes this deterministic. The real trigger is a dead adapter, but
     * that leaves the outcome to a race between the reader thread spotting EOF and the send itself
     * failing; a local close removes the reader from the picture entirely, because it does not
     * drive the FSM at all (see {@code connectionClosedOnLive}). So the status asserted here can
     * only have come from the failed send inside the transition action.
     */
    @Test
    void sendFailureOnLaunchReportsConnectionLost() throws Exception {
        GpgNetConnection conn = new GpgNetConnection(gpgnet.port());
        MockGameLifecycle lifecycle = lifecycleOn(conn, null, null);
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        conn.close();
        // matchBegins sends "Launching", which now fails.
        lifecycle.launchMatch();

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.SERVER_CONNECTION_LOST, lifecycle.getExitStatus());
    }

    /**
     * The match-end case from the issue: the GPGNet link is gone when the closing frames are sent.
     * Previously this reported the initial FAILED, because throwing into ENDED skips the OK
     * assignment at the end of {@code gameEnds} and nothing else set a status — so the same
     * physical event reported SERVER_CONNECTION_LOST or FAILED depending on which path won.
     */
    @Test
    void sendFailureOnMatchEndReportsConnectionLost() throws Exception {
        GpgNetConnection conn = new GpgNetConnection(gpgnet.port());
        MockGameLifecycle lifecycle = lifecycleOn(conn, null, null);
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(1, TimeUnit.SECONDS);

        lifecycle.launchMatch();
        lifecycle.stateReached(GameState.LIVE).get(1, TimeUnit.SECONDS);

        conn.close();
        // gameEnds sends the game results, stats and closing frames, which now fail.
        lifecycle.endMatch();

        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
        assertEquals(
                MockGameLifecycle.ExitStatus.SERVER_CONNECTION_LOST, lifecycle.getExitStatus());
    }

    @Test
    void serverDisconnectionOnInitializing() throws Exception {
        // Stop the server now.
        gpgnet.stop();
        // GpgNetConnection.start fails after 10 tries (default) and sends a ServerDisconnected.
        MockGameLifecycle lifecycle =
                lifecycleOn(
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.stateReached(GameState.ENDED).get(3, TimeUnit.SECONDS);
        assertEquals(MockGameLifecycle.ExitStatus.SERVER_NOT_CONNECTED, lifecycle.getExitStatus());
    }

    @Test
    void malformedJoinGameCommand() throws Exception {
        MockGameLifecycle lifecycle =
                lifecycleOn(
                        new GpgNetConnection(gpgnet.port()),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
        lifecycle.stateReached(GameState.IDLE).get(1, TimeUnit.SECONDS);
        gpgnet.sendFrame(
                new GpgNetFrame("CreateLobby", List.of(0, config.lobbyPort(), "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(1, TimeUnit.SECONDS);
        // JoinGame with no arguments.
        gpgnet.sendFrame(new GpgNetFrame("JoinGame", List.of()));
        // Causes ENDED
        lifecycle.stateReached(GameState.ENDED).get(1, TimeUnit.SECONDS);
        // Generic failure exit status
        assertEquals(MockGameLifecycle.ExitStatus.FAILED, lifecycle.getExitStatus());
    }
}
